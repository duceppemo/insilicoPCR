package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.insilicopcr.Sample;
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
    private static final Map<String, String> PRIMERS = Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20);
    private static final Map<String, Integer> LENGTHS = Map.of("contig_1", 1000, "contig_2", 800, "plasmid", 3000);

    private static Sample genome(BlastResult... hits) {
        Sample sample = new Sample();
        sample.setName("genome");
        sample.setFileType("fasta");
        for (BlastResult hit : hits) {
            sample.addBlastResult(hit.subjectID(), hit);
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
    void convergentPrimersOnOneContigAreNotASplit() {
        // A pair facing each other on one contig is a product, or none for its own reasons (here: too long).
        Sample sample = genome(plusHit("assay-F", "plasmid", 11), minusHit("assay-R", "plasmid", 2901));

        assertTrue(find(sample, new AmpliconOptions(0, 0, 5, 0, 5, 0, 1000)).isEmpty());
    }

    @Test
    void aProductAcrossTheBreakLongerThanTheLimitIsNotReported() {
        // Forward 500 bp from the end of contig_1, reverse 400 bp from the start of contig_2.
        Sample sample = genome(plusHit("assay-F", "contig_1", 481), minusHit("assay-R", "contig_2", 401));

        assertEquals(1, find(sample, DEFAULTS).size());
        assertTrue(find(sample, new AmpliconOptions(0, 0, 5, 0, 5, 0, 900)).isEmpty());
        assertEquals(1, find(sample, new AmpliconOptions(0, 0, 5, 0, 5, 0, 0)).size(), "0 is no limit");
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
        Map<String, String> withProbe = Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20, "assay-P", PRIMER_20);
        Sample sample = genome(plusHit("assay-F", "contig_1", 941), minusHit("assay-R", "contig_2", 31));

        Split split = SplitAcrossContigs.find(sample, withProbe, DEFAULTS, () -> LENGTHS).getFirst();

        assertTrue(split.message().contains("(the probe cannot be checked across the break)"), split.message());
    }
}
