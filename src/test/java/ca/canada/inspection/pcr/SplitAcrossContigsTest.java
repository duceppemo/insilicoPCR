package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.SplitAcrossContigs.Split;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static ca.canada.inspection.pcr.TestHits.minusHit;
import static ca.canada.inspection.pcr.TestHits.plusHit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplitAcrossContigsTest {

    private static final AmpliconOptions DEFAULTS = AmpliconOptions.withMismatches(0);
    private static final PrimerPanel PRIMERS = PrimerPanel.of(Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20));
    private static final Map<String, Integer> LENGTHS = Map.of("contig_1", 1000, "contig_2", 800, "plasmid", 3000);

    private static Sample genome(BlastResult... hits) {
        Sample sample = new Sample("genome");
        sample.setFileType(Sample.FileType.FASTA);
        for (BlastResult hit : hits) {
            sample.hits().add(hit.subjectID(), hit);
        }
        return sample;
    }

    private static List<Split> find(Sample sample, AmpliconOptions options) {
        return SplitAcrossContigs.find(sample, PRIMERS, options, () -> LENGTHS);
    }

    @Test
    void primersPointingOffTheEndsOfTwoContigsMayHaveAProductAcrossTheBreak() {
        // Forward at 941-960(+) of 1000: 40 bp to the end; reverse at 31-50(-) of contig_2: 30 bp to its start.
        Sample sample = genome(plusHit("assay-F", "contig_1", 941), minusHit("assay-R", "contig_2", 31));

        List<Split> splits = find(sample, DEFAULTS);

        assertEquals(1, splits.size());
        Split split = splits.getFirst();
        assertEquals("assay", split.assay());
        assertEquals(40, split.forwardToEnd());
        assertEquals(30, split.reverseToEnd());
        assertEquals(60 + 50, split.minimumSize()); // 941..1000 and 1..50
        assertFalse(split.sameContig());
        assertEquals("sample genome, assay assay: no product, but the primers bind near the ends of two contigs:"
                + " assay-F at 941-960(+), 40 bp from the end on contig_1, and assay-R at 31-50(-), 30 bp from the"
                + " end on contig_2. Joined, they would give a product of at least 110 bp: the assembly may break"
                + " inside it. Check with a more complete assembly, or with the reads.", split.message());
    }

    @Test
    void aProductAcrossTheBreakIsNeverSaidShorterThanItsLongerPrimer() {
        // Forward on the last 12 bases of contig_1, reverse on the first 6 of contig_2: 18 bases on the contigs, but a
        // product holds each 20-mer in full (two 17-mers cut after 7 bases made "at least 15 bp"). The bases past
        // the ends are not added: they may be on the other contig, where contigs overlap.
        BlastResult forward = TestHits.truncatedPlusHit("assay-F", "contig_1", 989, 1, 12, PRIMER_20);
        BlastResult full = minusHit("assay-R", "contig_2", 1);
        BlastResult reverse = new BlastResult("contig_2", "assay-R", 0, 0, 1, 6, 6, 1, 20, 6,
                full.qseq().substring(0, 6), full.sseq().substring(0, 6), PRIMER_20);
        BlastResult longer = new BlastResult("contig_2", "assay-R", 0, 0, 1, 14, 14, 1, 20, 14,
                full.qseq().substring(0, 14), full.sseq().substring(0, 14), PRIMER_20);

        assertEquals(20, find(cut(forward, reverse), DEFAULTS).getFirst().minimumSize());
        assertEquals(12 + 14, find(cut(forward, longer), DEFAULTS).getFirst().minimumSize());
    }

    private static Sample cut(BlastResult forward, BlastResult reverse) {
        Sample sample = new Sample("genome");
        sample.setFileType(Sample.FileType.FASTA);
        sample.hits().addCutByEnd("assay-F", forward);
        sample.hits().addCutByEnd("assay-R", reverse);
        return sample;
    }

    @Test
    void primersPointingOffBothEndsOfOneContigMayHaveAProductAcrossACircularEnd() {
        // Forward near the end pointing right, reverse near the start pointing left: divergent on the contig.
        Sample sample = genome(plusHit("assay-F", "plasmid", 2901), minusHit("assay-R", "plasmid", 81));

        Split split = find(sample, DEFAULTS).getFirst();

        assertTrue(split.sameContig());
        assertEquals(100 + 100, split.minimumSize());
        assertTrue(split.message().contains("near both ends of plasmid") && split.message().contains(
                "If plasmid is circular (a plasmid or a closed chromosome), a product of at least 200 bp spans its end"),
                split.message());
    }

    @Test
    void primersOnOneStrandOfOneContigAreNotASplit() {
        // Both pointing right: no product across the end either, whatever the contig's shape.
        Sample sample = genome(plusHit("assay-F", "plasmid", 2901), plusHit("assay-R", "plasmid", 81));

        assertTrue(find(sample, DEFAULTS).isEmpty());
    }

    @Test
    void anAssayOnTheMinusStrandPointingOffBothEndsIsASplitToo() {
        // The forward primer near the start pointing left, the reverse near the end pointing right.
        Sample sample = genome(minusHit("assay-F", "plasmid", 81), plusHit("assay-R", "plasmid", 2901));

        List<Split> splits = find(sample, DEFAULTS);

        assertEquals(1, splits.size());
        assertTrue(splits.getFirst().sameContig());
        assertEquals(100 + 100, splits.getFirst().minimumSize());
    }

    @Test
    void aSplitIsWarnedAboutUpToTheLongestProductAndNoFurther() {
        // At least 200 bp across the end: within -l 200, beyond -l 199.
        Sample sample = genome(plusHit("assay-F", "plasmid", 2901), minusHit("assay-R", "plasmid", 81));

        assertEquals(1, find(sample, AmpliconOptions.builder().maxLength(200).build()).size());
        assertTrue(find(sample, AmpliconOptions.builder().maxLength(199).build()).isEmpty());
    }

    @Test
    void convergentPrimersOnOneContigAreNotASplit() {
        // A pair facing each other on one contig is a product, or none for its own reasons: here too
        // short for --min-length, and short enough for the split's own limit, so that only the
        // convergence rule keeps it from being a split.
        Sample sample = genome(plusHit("assay-F", "plasmid", 11), minusHit("assay-R", "plasmid", 211));

        assertTrue(find(sample, AmpliconOptions.builder().minLength(500).build()).isEmpty());
    }

    @Test
    void contigsOfOverAGigabaseDoNotOverflowIntoANegativeSize() {
        Map<String, Integer> lengths = Map.of("chr1", 1_200_000_000, "chr2", 1_200_000_000);
        // Each primer over a gigabase from the end it points to: 2.4 Gb together, more than an int holds.
        Sample sample = genome(plusHit("assay-F", "chr1", 1_000), minusHit("assay-R", "chr2", 1_199_999_000));
        assertTrue(SplitAcrossContigs.find(sample, PRIMERS, DEFAULTS, () -> lengths).isEmpty());
    }

    @Test
    void aProductAcrossTheBreakLongerThanTheLimitIsNotReported() {
        // Forward 500 bp from the end of contig_1, reverse 400 bp from the start of contig_2.
        Sample sample = genome(plusHit("assay-F", "contig_1", 481), minusHit("assay-R", "contig_2", 401));

        assertEquals(1, find(sample, DEFAULTS).size());
        assertTrue(find(sample, new AmpliconOptions(0, 0, 5, 0, 5, 0, 900)).isEmpty());
        assertEquals(1, find(sample, new AmpliconOptions(0, 0, 5, 0, 5, 0, 0)).size(), "-l 0");
    }

    @Test
    void withoutALimitAProductAcrossTheBreakGoesNoFurtherThanTheReadExtension() {
        // -l 0: any two primers pointing off two contig ends, megabases apart, were a split.
        Map<String, Integer> lengths = Map.of("chr1", 3_000_000, "chr2", 2_000_000);
        Sample far = genome(plusHit("assay-F", "chr1", 1_900_000), minusHit("assay-R", "chr2", 500_000));
        Sample near = genome(plusHit("assay-F", "chr1", 2_998_000), minusHit("assay-R", "chr2", 1_000));
        AmpliconOptions unlimited = new AmpliconOptions(0, 0, 5, 0, 5, 0, 0);

        assertTrue(SplitAcrossContigs.find(far, PRIMERS, unlimited, () -> lengths).isEmpty());
        assertEquals(1, SplitAcrossContigs.find(near, PRIMERS, unlimited, () -> lengths).size());
    }

    @Test
    void theClosestPairIsGiven() {
        Sample sample = genome(plusHit("assay-F", "contig_1", 541), plusHit("assay-F", "contig_1", 941),
                minusHit("assay-R", "contig_2", 31));

        assertEquals(941, find(sample, DEFAULTS).getFirst().forward().qstart());
    }

    @Test
    void anAssayWithAProductIsNotASplit() {
        Sample sample = genome(plusHit("assay-F", "contig_1", 101), minusHit("assay-R", "contig_1", 301),
                minusHit("assay-R", "contig_2", 31));

        assertTrue(find(sample, DEFAULTS).isEmpty());
    }

    @Test
    void aPrimerThatDoesNotBindIsAnOrdinaryNegativeAndTheFileIsNotRead() {
        Sample sample = genome(plusHit("assay-F", "contig_1", 941));
        AtomicInteger reads = new AtomicInteger();

        assertTrue(SplitAcrossContigs.find(sample, PRIMERS, DEFAULTS, () -> {
            reads.incrementAndGet();
            return LENGTHS;
        }).isEmpty());
        assertEquals(0, reads.get());
    }

    @Test
    void aContigOfUnknownLengthIsLeftOut() {
        Sample sample = genome(plusHit("assay-F", "contig_1", 941), minusHit("assay-R", "elsewhere", 31));

        assertTrue(find(sample, DEFAULTS).isEmpty());
    }

    @Test
    void aProbeAssaySaysTheProbeIsNotChecked() {
        PrimerPanel withProbe = PrimerPanel.of(Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20, "assay-P", PRIMER_20));
        Sample sample = genome(plusHit("assay-F", "contig_1", 941), minusHit("assay-R", "contig_2", 31));

        Split split = SplitAcrossContigs.find(sample, withProbe, DEFAULTS, () -> LENGTHS).getFirst();

        assertTrue(split.message().contains("(the probe cannot be checked across the break)"), split.message());
    }
}
