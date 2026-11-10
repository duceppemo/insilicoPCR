package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadCall;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadLevelCallerTest {

    private static final AmpliconOptions DEFAULTS = AmpliconOptions.withMismatches(0);
    private static final Map<String, String> PRIMERS = Map.of(
            "assay-F", PRIMER_20, "assay-R", PRIMER_20, "assay-P", PRIMER_20);

    private static Sample reads() {
        Sample reads = FastqDiagnosticsTest.readsSample();
        reads.addContig("read_1", "ERR1.5 5/1");
        reads.addContig("read_2", "ERR1.9 9/2");
        return reads;
    }

    @Test
    void readHoldingCompleteAmpliconWithProbeIsCalled() {
        Sample reads = reads();
        for (String read : List.of("read_1", "read_2")) {
            reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", read, 11));
            reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", read, 61));
            reads.addBlastResult("assay-P", TestHits.plusHit("assay-P", read, 35));
        }

        ReadEvidence evidence = ReadLevelCaller.evaluate(reads, Map.of("read_1", 101, "read_2", 101), PRIMERS, DEFAULTS);
        ReadCall call = evidence.products("assay").getFirst();

        assertEquals(2, call.supportingReads());
        assertEquals(Set.of(70), call.sizes());
        assertEquals("read_1", call.row().get(ConsolidatedReport.CONTIG_COLUMN));
        assertEquals(Set.of("read_1", "read_2"), evidence.readsWithOligo().get("assay-F"));

        List<String> row = ReadLevelCaller.reportRow(call);
        assertEquals("70", row.get(ConsolidatedReport.AMPLICON_SIZE_COLUMN));
        assertEquals(2, call.informativeReads());
        assertTrue(call.isReportable());
        assertTrue(row.get(ConsolidatedReport.CONTIG_DESCRIPTION_COLUMN).startsWith(
                "FASTQ read-level call: complete amplicon in 2 of 2 informative read(s) (100%), not in the mini-assembly"),
                row.get(ConsolidatedReport.CONTIG_DESCRIPTION_COLUMN));
        assertTrue(row.get(ConsolidatedReport.CONTIG_DESCRIPTION_COLUMN).endsWith("representative read ERR1.5 5/1"));
        assertEquals("assay-P", row.get(13), "probe columns are kept");
    }

    @Test
    void minorityOfInformativeReadsIsNotReportable() {
        // Two reads hold the amplicon; eight more carry the forward primer and are long
        // enough to have shown it, but do not: 2 of 10 informative reads (20%).
        Sample reads = reads();
        Map<String, Integer> lengths = new java.util.HashMap<>();
        for (int i = 1; i <= 10; i++) {
            String read = "read_" + i;
            lengths.put(read, 101);
            reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", read, 11));
            if (i <= 2) {
                reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", read, 61));
                reads.addBlastResult("assay-P", TestHits.plusHit("assay-P", read, 35));
            }
        }
        // A read carrying the forward primer too close to its end is not informative.
        lengths.put("read_11", 40);
        reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", "read_11", 11));

        ReadCall call = ReadLevelCaller.evaluate(reads, lengths, PRIMERS, DEFAULTS).products("assay").getFirst();

        assertEquals(2, call.supportingReads());
        assertEquals(10, call.informativeReads());
        assertTrue(!call.isReportable());

        // The same 20% support passes a lower --read-min-fraction.
        ReadCall lenient = ReadLevelCaller.evaluate(reads, lengths, PRIMERS, DEFAULTS, 0.2).products("assay").getFirst();
        assertTrue(lenient.isReportable());
        assertEquals("at least 2 reads or read pairs and 20% of informative reads", lenient.thresholdSummary());
        assertThrows(IllegalArgumentException.class,
                () -> ReadLevelCaller.evaluate(reads, lengths, PRIMERS, DEFAULTS, 1.1));
    }

    @Test
    void matesOfOneUnmergedPairCountAsOneFragment() {
        // Both mates of one pair hold the whole amplicon: one molecule, so 1 supporting
        // fragment, below the 2-read minimum. As separate reads they would be called.
        Sample reads = reads();
        for (String read : List.of("read_1", "read_2")) {
            reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", read, 11));
            reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", read, 61));
            reads.addBlastResult("assay-P", TestHits.plusHit("assay-P", read, 35));
        }
        Map<String, Integer> lengths = Map.of("read_1", 101, "read_2", 101);

        ReadCall paired = ReadLevelCaller.evaluate(reads, lengths, Map.of("read_1", "read_1", "read_2", "read_1"),
                PRIMERS, DEFAULTS, ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION).products("assay").getFirst();
        assertEquals(1, paired.supportingReads());
        assertEquals(1, paired.informativeReads());
        assertTrue(!paired.isReportable());

        ReadCall separate = ReadLevelCaller.evaluate(reads, lengths, PRIMERS, DEFAULTS).products("assay").getFirst();
        assertEquals(2, separate.supportingReads());
        assertTrue(separate.isReportable());
    }

    @Test
    void singleSupportingReadIsNotReportable() {
        Sample reads = reads();
        reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", "read_1", 11));
        reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", "read_1", 61));
        reads.addBlastResult("assay-P", TestHits.plusHit("assay-P", "read_1", 35));

        ReadCall call = ReadLevelCaller.evaluate(reads, Map.of("read_1", 101), PRIMERS, DEFAULTS).products("assay").getFirst();

        assertEquals(1.0, call.supportFraction());
        assertTrue(!call.isReportable(), "one read is not enough");
    }

    @Test
    void primersOnDifferentMatesAreNotPaired() {
        // Mates carry separate read IDs, so a forward hit on one mate and a reverse
        // hit on the other must not form an amplicon.
        Sample reads = reads();
        reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", "read_1", 11));
        reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", "read_2", 61));
        reads.addBlastResult("assay-P", TestHits.plusHit("assay-P", "read_1", 35));

        ReadEvidence evidence = ReadLevelCaller.evaluate(reads, Map.of("read_1", 101, "read_2", 101), PRIMERS, DEFAULTS);

        assertTrue(evidence.calls().isEmpty());
    }

    @Test
    void readStartingInsideThePrimerIsNotACompleteAmplicon() {
        // The read begins at primer base 4: the 5'-truncation allowance would accept the
        // hit, but the read lacks the product's first 3 bases, so it must not be called.
        Sample reads = reads();
        reads.addBlastResult("assay-F", TestHits.truncatedPlusHit("assay-F", "read_1", 1, 4, 20, PRIMER_20));
        reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", "read_1", 51));
        reads.addBlastResult("assay-P", TestHits.plusHit("assay-P", "read_1", 25));

        ReadEvidence evidence = ReadLevelCaller.evaluate(reads, Map.of("read_1", 101), PRIMERS, DEFAULTS);

        assertTrue(evidence.calls().isEmpty());
        assertEquals(null, evidence.readsWithOligo().get("assay-F"), "a clipped primer is not counted as carried");
    }

    @Test
    void ampliconWithoutRequiredProbeIsNotCalled() {
        Sample reads = reads();
        reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", "read_1", 11));
        reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", "read_1", 61));

        assertTrue(ReadLevelCaller.evaluate(reads, Map.of("read_1", 101), PRIMERS, DEFAULTS).calls().isEmpty());
    }

    @Test
    void checkableLengthRunsFromPrimerFivePrimeEndToReadEnd() {
        Sample reads = reads();
        reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", "read_1", 31));   // 101 - 31 + 1 = 71
        reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", "read_2", 61));  // ends at 80

        ReadEvidence evidence = ReadLevelCaller.evaluate(reads, Map.of("read_1", 101, "read_2", 150), PRIMERS, DEFAULTS);

        assertEquals(80, evidence.checkableLength().get("assay"));
    }

    // ---- reads at the product's locus versus reads carrying a primer elsewhere ----

    private static final String FLANK = "GATTACAGGCTTAACCGGTTAAGCCATGCA";       // 30 bases after the forward primer
    private static final String OTHER = "TTTTCCCCAAAAGGGGTTTTCCCCAAAAGG";       // an unrelated place in the genome
    private static final Map<String, String> PCR_PRIMERS = Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20);

    /** A 101-base read: 10 bases, the forward primer at 11-30, then {@code afterPrimer}, then filler. */
    private static String read(String afterPrimer) {
        String start = "A".repeat(10) + PRIMER_20 + afterPrimer;
        return start + "C".repeat(101 - start.length());
    }

    /**
     * Two reads hold a 70 bp amplicon (forward primer at 11, reverse at 61); {@code others}
     * more carry only the forward primer, followed by {@code afterPrimer}.
     */
    private static ReadCall callWithOtherReads(int others, String afterPrimer) {
        Sample reads = reads();
        Map<String, Integer> lengths = new HashMap<>();
        Map<String, String> sequences = new HashMap<>();
        for (int i = 1; i <= 2 + others; i++) {
            String read = "read_" + i;
            lengths.put(read, 101);
            reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", read, 11));
            if (i <= 2) {
                reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", read, 61));
            }
            sequences.put(read, read(i <= 2 ? FLANK : afterPrimer));
        }
        return ReadLevelCaller.evaluate(reads, lengths, Map.of(), PCR_PRIMERS, DEFAULTS,
                ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION, ids -> sequences).products("assay").getFirst();
    }

    @Test
    void readsCarryingThePrimerElsewhereInTheGenomeAreNotInformative() {
        // The forward primer also matches 50 other places. Those reads would never have
        // shown the product, so they must not dilute the two reads that hold it.
        ReadCall call = callWithOtherReads(50, OTHER);

        assertEquals(2, call.supportingReads());
        assertEquals(2, call.informativeReads());
        assertEquals(50, call.elsewhereReads());
        assertTrue(call.isReportable());
    }

    @Test
    void readsOfTheSameLocusWithoutTheAmpliconStayInformative() {
        // The same 50 reads at the product's own locus (same bases after the primer) but
        // without the reverse primer: the sample mostly lacks the target, as with a few
        // contaminating reads in a related organism. 2 of 52 is not a call.
        ReadCall call = callWithOtherReads(50, FLANK);

        assertEquals(52, call.informativeReads());
        assertEquals(0, call.elsewhereReads());
        assertTrue(!call.isReportable());
    }

    @Test
    void divergedCopyOfTheLocusStaysInformative() {
        // A related organism's copy: 5 of the 30 bases after the primer differ (83% identity).
        char[] diverged = FLANK.toCharArray();
        for (int position : new int[]{2, 8, 14, 20, 26}) {
            diverged[position] = diverged[position] == 'A' ? 'C' : 'A';
        }
        assertEquals(12, callWithOtherReads(10, new String(diverged)).informativeReads());

        // One base missing after the primer shifts everything behind it: still the locus.
        assertEquals(12, callWithOtherReads(10, FLANK.substring(0, 5) + FLANK.substring(6) + "C").informativeReads());
    }

    @Test
    void withoutReadSequencesEveryReadCarryingThePrimerIsInformative() {
        Sample reads = reads();
        Map<String, Integer> lengths = new HashMap<>();
        for (int i = 1; i <= 12; i++) {
            lengths.put("read_" + i, 101);
            reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", "read_" + i, 11));
            if (i <= 2) {
                reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", "read_" + i, 61));
            }
        }
        ReadCall call = ReadLevelCaller.evaluate(reads, lengths, PCR_PRIMERS, DEFAULTS).products("assay").getFirst();

        assertEquals(12, call.informativeReads(), "cannot be judged: counted, which only lowers the support");
        assertEquals(0, call.elsewhereReads());
    }

    @Test
    void flankIsReadInThePrimersDirection() {
        String sequence = read(FLANK);
        assertEquals(FLANK, ReadLevelCaller.flank(sequence, TestHits.plusHit("assay-F", "r", 11), 30));
        // A minus-strand primer at 61-80 extends leftward: the 30 bases before it, reverse-complemented.
        assertEquals("TGCATGGCTTAACCGGTTAAGCCTGTAATC", ReadLevelCaller.flank(sequence, TestHits.minusHit("assay-R", "r", 61), 30));
        assertNull(ReadLevelCaller.flank(sequence, TestHits.plusHit("assay-F", "r", 81), 30), "read ends first");
        assertNull(ReadLevelCaller.flank(sequence, TestHits.plusHit("assay-F", "r", 11), 9), "too few bases to judge by");
    }

    @Test
    void editDistanceCountsSubstitutionsAndIndels() {
        assertEquals(0, ReadLevelCaller.editDistance("ACGTACGT", "ACGTACGT", 8));
        assertEquals(1, ReadLevelCaller.editDistance("ACGTACGT", "ACGAACGT", 8));
        assertEquals(2, ReadLevelCaller.editDistance("ACGTACGT", "CGTACGTA", 8), "one base lost, one gained");
        assertEquals(0, ReadLevelCaller.editDistance("ACGTTTTT", "ACGTAAAA", 4), "only the first bases are compared");
    }

    // ---- one assay, several products ----

    @Test
    void productsOfDifferentSizesAreCalledOnTheirOwnSupport() {
        // Six reads hold the 70 bp target. Two reads from another place where both primers
        // land give a 40 bp product; eight more reads cover that place without holding it.
        Sample reads = reads();
        Map<String, Integer> lengths = new HashMap<>();
        Map<String, String> sequences = new HashMap<>();
        for (int i = 1; i <= 16; i++) {
            String read = "read_" + i;
            lengths.put(read, 101);
            reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", read, 11));
            if (i <= 6) {
                reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", read, 61));
            } else if (i <= 8) {
                reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", read, 31));
            }
            sequences.put(read, read(i <= 6 ? FLANK : OTHER));
        }

        List<ReadCall> products = ReadLevelCaller.evaluate(reads, lengths, Map.of(), PCR_PRIMERS, DEFAULTS,
                ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION, ids -> sequences).products("assay");

        assertEquals(2, products.size());
        ReadCall offTarget = products.get(0);
        assertEquals(Set.of(40), offTarget.sizes());
        assertEquals(2, offTarget.supportingReads());
        assertEquals(10, offTarget.informativeReads());
        assertTrue(!offTarget.isReportable(), "2 of 10 reads at its own locus");
        assertEquals("40", offTarget.row().get(ConsolidatedReport.AMPLICON_SIZE_COLUMN));

        ReadCall target = products.get(1);
        assertEquals(Set.of(70), target.sizes());
        assertEquals(6, target.supportingReads());
        assertEquals(6, target.informativeReads(), "the other locus does not count against the target");
        assertTrue(target.isReportable());
        assertEquals("70", target.row().get(ConsolidatedReport.AMPLICON_SIZE_COLUMN));
    }

    @Test
    void lengthVariantsOfOneLocusAreOneProduct() {
        Sample reads = reads();
        Map<String, Integer> lengths = new HashMap<>();
        int[] reverseStart = {61, 61, 62, 71};   // 70, 70, 71 and 80 bp
        for (int i = 0; i < reverseStart.length; i++) {
            String read = "read_" + (i + 1);
            lengths.put(read, 101);
            reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", read, 11));
            reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", read, reverseStart[i]));
        }

        List<ReadCall> products = ReadLevelCaller.evaluate(reads, lengths, PCR_PRIMERS, DEFAULTS).products("assay");

        assertEquals(1, products.size(), "70, 71 and 80 bp are within " + ReadLevelCaller.PRODUCT_SIZE_GAP + " bp of a neighbour");
        assertEquals(Set.of(70, 71, 80), products.getFirst().sizes());
        assertEquals("70,71,80", products.getFirst().sizeSummary());
        assertTrue(products.getFirst().matches(60) && products.getFirst().matches(90));
        assertTrue(!products.getFirst().matches(59) && !products.getFirst().matches(91));
    }
}
