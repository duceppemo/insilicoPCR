package ca.canada.inspection.pcr;

import ca.canada.inspection.model.Hits;
import ca.canada.inspection.model.Sample;
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
    private static final PrimerPanel PRIMERS = PrimerPanel.of(Map.of(
            "assay-F", PRIMER_20, "assay-R", PRIMER_20, "assay-P", PRIMER_20));

    private static Sample reads() {
        Sample reads = FastqDiagnosticsTest.readsSample();
        reads.hits().describe("read_1", "ERR1.5 5/1");
        reads.hits().describe("read_2", "ERR1.9 9/2");
        return reads;
    }

    @Test
    void readHoldingCompleteAmpliconWithProbeIsCalled() {
        Sample reads = reads();
        for (String read : List.of("read_1", "read_2")) {
            reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 11));
            reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, 61));
            reads.hits().add("assay-P", TestHits.plusHit("assay-P", read, 35));
        }

        ReadEvidence evidence = TestEvidence.evaluate(reads, Map.of("read_1", 101, "read_2", 101), PRIMERS, DEFAULTS);
        ReadCall call = evidence.products("assay").getFirst();

        assertEquals(2, call.supportingReads());
        assertEquals(Set.of(70), call.sizes());
        assertEquals("read_1", call.row().contig());
        assertEquals(Set.of("read_1", "read_2"), evidence.readsWithOligo().get("assay-F"));

        ReportRow row = ReadLevelCaller.reportRow(call);
        assertEquals(70, row.size());
        assertEquals(2, call.informativeReads());
        assertTrue(call.isReportable());
        assertTrue(row.contigDescription().startsWith(
                "FASTQ read-level call: complete amplicon in 2 of 2 informative read(s) (100%), not in the mini-assembly"),
                row.contigDescription());
        assertTrue(row.contigDescription().endsWith("representative read ERR1.5 5/1"));
        assertEquals("assay-P", row.probeId(), "probe columns are kept");
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
            reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 11));
            if (i <= 2) {
                reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, 61));
                reads.hits().add("assay-P", TestHits.plusHit("assay-P", read, 35));
            }
        }
        // A read carrying the forward primer too close to its end is not informative.
        lengths.put("read_11", 40);
        reads.hits().add("assay-F", TestHits.plusHit("assay-F", "read_11", 11));

        ReadCall call = TestEvidence.evaluate(reads, lengths, PRIMERS, DEFAULTS).products("assay").getFirst();

        assertEquals(2, call.supportingReads());
        assertEquals(10, call.informativeReads());
        assertTrue(!call.isReportable());

        // The same 20% support passes a lower --read-min-fraction.
        ReadCall lenient = TestEvidence.evaluate(reads, lengths, PRIMERS, DEFAULTS, 0.2).products("assay").getFirst();
        assertTrue(lenient.isReportable());
        assertEquals("at least 2 reads or read pairs and 20% of informative reads", lenient.thresholdSummary());
        assertThrows(IllegalArgumentException.class,
                () -> TestEvidence.evaluate(reads, lengths, PRIMERS, DEFAULTS, 1.1));
    }

    @Test
    void matesOfOneUnmergedPairCountAsOneFragment() {
        // Both mates of one pair hold the whole amplicon: one molecule, so 1 supporting
        // fragment, below the 2-read minimum. As separate reads they would be called.
        Sample reads = reads();
        for (String read : List.of("read_1", "read_2")) {
            reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 11));
            reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, 61));
            reads.hits().add("assay-P", TestHits.plusHit("assay-P", read, 35));
        }
        Map<String, Integer> lengths = Map.of("read_1", 101, "read_2", 101);

        ReadCall paired = TestEvidence.evaluate(reads, lengths, Map.of("read_1", "read_1", "read_2", "read_1"),
                PRIMERS, DEFAULTS, ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION).products("assay").getFirst();
        assertEquals(1, paired.supportingReads());
        assertEquals(1, paired.informativeReads());
        assertTrue(!paired.isReportable());

        ReadCall separate = TestEvidence.evaluate(reads, lengths, PRIMERS, DEFAULTS).products("assay").getFirst();
        assertEquals(2, separate.supportingReads());
        assertTrue(separate.isReportable());
    }

    @Test
    void singleSupportingReadIsNotReportable() {
        Sample reads = reads();
        reads.hits().add("assay-F", TestHits.plusHit("assay-F", "read_1", 11));
        reads.hits().add("assay-R", TestHits.minusHit("assay-R", "read_1", 61));
        reads.hits().add("assay-P", TestHits.plusHit("assay-P", "read_1", 35));

        ReadCall call = TestEvidence.evaluate(reads, Map.of("read_1", 101), PRIMERS, DEFAULTS).products("assay").getFirst();

        assertEquals(1.0, call.supportFraction());
        assertTrue(!call.isReportable(), "one read is not enough");
    }

    @Test
    void primersOnDifferentMatesAreNotPaired() {
        // Mates carry separate read IDs, so a forward hit on one mate and a reverse
        // hit on the other must not form an amplicon.
        Sample reads = reads();
        reads.hits().add("assay-F", TestHits.plusHit("assay-F", "read_1", 11));
        reads.hits().add("assay-R", TestHits.minusHit("assay-R", "read_2", 61));
        reads.hits().add("assay-P", TestHits.plusHit("assay-P", "read_1", 35));

        ReadEvidence evidence = TestEvidence.evaluate(reads, Map.of("read_1", 101, "read_2", 101), PRIMERS, DEFAULTS);

        assertTrue(evidence.calls().isEmpty());
    }

    @Test
    void readStartingInsideThePrimerIsNotACompleteAmplicon() {
        // The read begins at primer base 4: the 5'-truncation allowance would accept the
        // hit, but the read lacks the product's first 3 bases, so it must not be called.
        Sample reads = reads();
        reads.hits().add("assay-F", TestHits.truncatedPlusHit("assay-F", "read_1", 1, 4, 20, PRIMER_20));
        reads.hits().add("assay-R", TestHits.minusHit("assay-R", "read_1", 51));
        reads.hits().add("assay-P", TestHits.plusHit("assay-P", "read_1", 25));

        ReadEvidence evidence = TestEvidence.evaluate(reads, Map.of("read_1", 101), PRIMERS, DEFAULTS);

        assertTrue(evidence.calls().isEmpty());
        assertEquals(null, evidence.readsWithOligo().get("assay-F"), "a clipped primer is not counted as carried");
    }

    @Test
    void ampliconWithoutRequiredProbeIsNotCalled() {
        Sample reads = reads();
        reads.hits().add("assay-F", TestHits.plusHit("assay-F", "read_1", 11));
        reads.hits().add("assay-R", TestHits.minusHit("assay-R", "read_1", 61));

        assertTrue(TestEvidence.evaluate(reads, Map.of("read_1", 101), PRIMERS, DEFAULTS).calls().isEmpty());
    }

    @Test
    void checkableLengthRunsFromPrimerFivePrimeEndToReadEnd() {
        Sample reads = reads();
        reads.hits().add("assay-F", TestHits.plusHit("assay-F", "read_1", 31));   // 101 - 31 + 1 = 71
        reads.hits().add("assay-R", TestHits.minusHit("assay-R", "read_2", 61));  // ends at 80

        ReadEvidence evidence = TestEvidence.evaluate(reads, Map.of("read_1", 101, "read_2", 150), PRIMERS, DEFAULTS);

        assertEquals(80, evidence.checkableLength().get("assay"));
    }

    // ---- reads at the product's locus versus reads carrying a primer elsewhere ----

    private static final String FLANK = "GATTACAGGCTTAACCGGTTAAGCCATGCA";       // 30 bases after the forward primer
    private static final String OTHER = "TTTTCCCCAAAAGGGGTTTTCCCCAAAAGG";       // an unrelated place in the genome
    private static final PrimerPanel PCR_PRIMERS = PrimerPanel.of(Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20));

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
            reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 11));
            if (i <= 2) {
                reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, 61));
            }
            sequences.put(read, read(i <= 2 ? FLANK : afterPrimer));
        }
        return ReadLevelCaller.evaluate(reads.getName(), reads.hits(), lengths, Map.of(), PCR_PRIMERS, DEFAULTS,
                ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION, ids -> sequences).products("assay").getFirst();
    }

    /**
     * Two reads hold a 70 bp amplicon (forward primer at 11, reverse at 61); {@code read_3} and {@code read_4},
     * mates of one pair, carry the forward primer only, followed by {@code afterPrimer3} and {@code afterPrimer4}.
     */
    private static ReadCall callWithOnePairWithoutIt(String afterPrimer3, String afterPrimer4) {
        Sample reads = reads();
        Map<String, Integer> lengths = new HashMap<>();
        Map<String, String> sequences = new HashMap<>();
        Map<String, String> after = Map.of("read_1", FLANK, "read_2", FLANK, "read_3", afterPrimer3, "read_4", afterPrimer4);
        for (String read : List.of("read_1", "read_2", "read_3", "read_4")) {
            lengths.put(read, 101);
            reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 11));
            sequences.put(read, read(after.get(read)));
        }
        for (String read : List.of("read_1", "read_2")) {
            reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, 61));
        }
        Map<String, String> fragments = Map.of("read_1", "read_1", "read_2", "read_2", "read_3", "read_3", "read_4", "read_3");
        return ReadLevelCaller.evaluate(reads.getName(), reads.hits(), lengths, fragments, PCR_PRIMERS, DEFAULTS,
                ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION, ids -> sequences).products("assay").getFirst();
    }

    @Test
    void matesWithoutTheProductCountAsOneInformativeFragment() {
        // Two reads hold it; one pair, both mates at the locus, does not: 3 informative molecules, not 4.
        ReadCall call = callWithOnePairWithoutIt(FLANK, FLANK);

        assertEquals(2, call.supportingReads());
        assertEquals(3, call.informativeReads());
    }

    @Test
    void aPairWithOneMateAtTheLocusIsInformativeAndNotElsewhere() {
        // read_3 carries the primer at another place, its mate read_4 at the locus: the pair is one
        // informative molecule, not counted among the reads carrying the primer elsewhere too.
        ReadCall call = callWithOnePairWithoutIt(OTHER, FLANK);

        assertEquals(3, call.informativeReads());
        assertEquals(0, call.elsewhereReads());
    }

    @Test
    void aReadIsInformativeWhenItReachesTheProductsLengthFromThePrimer() {
        // The product is 70 bp from the forward primer's 5' end at 11: a read of 80 bases reaches it
        // exactly and would have shown it; one of 79 stops a base short.
        for (int length : new int[]{80, 79}) {
            Sample reads = reads();
            Map<String, Integer> lengths = new HashMap<>(Map.of("read_1", 101, "read_2", 101, "read_3", length));
            for (String read : List.of("read_1", "read_2", "read_3")) {
                reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 11));
            }
            for (String read : List.of("read_1", "read_2")) {
                reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, 61));
            }

            ReadCall call = TestEvidence.evaluate(reads, lengths, PCR_PRIMERS, DEFAULTS).products("assay").getFirst();

            assertEquals(length == 80 ? 3 : 2, call.informativeReads(), "a read of " + length);
        }
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
    void aSecondCopyOfThePrimerOnAProductsReadDoesNotSetTheLocus() {
        // The two reads holding the product carry the forward primer again after it (81-100),
        // followed by the bases of the unrelated place. Only the primer that bounds the product
        // tells what follows it at the locus: the 50 reads of the other place stay elsewhere.
        Sample reads = reads();
        Map<String, Integer> lengths = new HashMap<>();
        Map<String, String> sequences = new HashMap<>();
        for (int i = 1; i <= 52; i++) {
            String read = "read_" + i;
            reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 11));
            if (i <= 2) {
                reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, 61));
                reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 81));
                String start = "A".repeat(10) + PRIMER_20 + FLANK + "C".repeat(20) + PRIMER_20 + OTHER;
                sequences.put(read, start + "C".repeat(151 - start.length()));
                lengths.put(read, 151);
            } else {
                sequences.put(read, read(OTHER));
                lengths.put(read, 101);
            }
        }
        ReadCall call = ReadLevelCaller.evaluate(reads.getName(), reads.hits(), lengths, Map.of(), PCR_PRIMERS,
                DEFAULTS, ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION, ids -> sequences).products("assay").getFirst();

        assertEquals(2, call.supportingReads());
        assertEquals(50, call.elsewhereReads());
        assertTrue(call.isReportable());
    }

    @Test
    void readsCallAnAssaysOwnProductOnlyNotOneOfOnePrimerOrOfTwoAssays() {
        // A read folding back on one primer, or reads of two assays' primers in one reaction: each became
        // a call (the A+B one at 100%: no primer of its own to count support from).
        Sample reads = reads();
        Map<String, Integer> lengths = new HashMap<>();
        for (int i = 1; i <= 2; i++) {
            lengths.put("fold_" + i, 101);
            reads.hits().add("a-F", TestHits.plusHit("a-F", "fold_" + i, 11));
            reads.hits().add("a-F", TestHits.minusHit("a-F", "fold_" + i, 61));
            lengths.put("chimera_" + i, 101);
            reads.hits().add("a-F", TestHits.plusHit("a-F", "chimera_" + i, 11));
            reads.hits().add("b-R", TestHits.minusHit("b-R", "chimera_" + i, 61));
        }
        Map<String, String> sequences = new java.util.LinkedHashMap<>();
        for (String id : List.of("a-F", "a-R", "b-F", "b-R")) {
            sequences.put(id, PRIMER_20);
        }
        PrimerPanel mixed = PrimerPanel.of(sequences, ExpectedSizes.NONE,
                MixGroups.fromHeaders(Map.of("a-F", "mix=one", "b-F", "mix=one")));

        ReadLevelCaller.ReadEvidence evidence = TestEvidence.evaluate(reads, lengths, mixed, DEFAULTS);

        assertEquals(List.of(), evidence.products("a"));
        assertEquals(List.of(), evidence.products("a+b"));
    }

    @Test
    void withoutReadSequencesEveryReadCarryingThePrimerIsInformative() {
        Sample reads = reads();
        Map<String, Integer> lengths = new HashMap<>();
        for (int i = 1; i <= 12; i++) {
            lengths.put("read_" + i, 101);
            reads.hits().add("assay-F", TestHits.plusHit("assay-F", "read_" + i, 11));
            if (i <= 2) {
                reads.hits().add("assay-R", TestHits.minusHit("assay-R", "read_" + i, 61));
            }
        }
        ReadCall call = TestEvidence.evaluate(reads, lengths, PCR_PRIMERS, DEFAULTS).products("assay").getFirst();

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
    void aShareBelowTheThresholdDoesNotReadAsReachingIt() {
        // 89 of 300 is 29.67%: "30%" next to "needs ... 30%" read as a contradiction.
        assertEquals("29.6%", ReadLevelCaller.percentBelow(89 / 300.0, 0.3));
        assertEquals("29.9%", ReadLevelCaller.percentBelow(0.2999, 0.3));
        // Any other share is a whole percent, as before.
        assertEquals("18%", ReadLevelCaller.percentBelow(3 / 17.0, 0.3));
        assertEquals("30%", ReadLevelCaller.percentBelow(0.3, 0.3));
        assertEquals("67%", ReadLevelCaller.percentBelow(2 / 3.0, 0.3));
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
            reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 11));
            if (i <= 6) {
                reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, 61));
            } else if (i <= 8) {
                reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, 31));
            }
            sequences.put(read, read(i <= 6 ? FLANK : OTHER));
        }

        List<ReadCall> products = ReadLevelCaller.evaluate(reads.getName(), reads.hits(), lengths, Map.of(), PCR_PRIMERS, DEFAULTS,
                ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION, ids -> sequences).products("assay");

        assertEquals(2, products.size());
        ReadCall offTarget = products.get(0);
        assertEquals(Set.of(40), offTarget.sizes());
        assertEquals(2, offTarget.supportingReads());
        assertEquals(10, offTarget.informativeReads());
        assertTrue(!offTarget.isReportable(), "2 of 10 reads at its own locus");
        assertEquals(40, offTarget.row().size());

        ReadCall target = products.get(1);
        assertEquals(Set.of(70), target.sizes());
        assertEquals(6, target.supportingReads());
        assertEquals(6, target.informativeReads(), "the other locus does not count against the target");
        assertTrue(target.isReportable());
        assertEquals(70, target.row().size());
    }

    @Test
    void lengthVariantsOfOneLocusAreOneProduct() {
        Sample reads = reads();
        Map<String, Integer> lengths = new HashMap<>();
        int[] reverseStart = {61, 61, 62, 71};   // 70, 70, 71 and 80 bp
        for (int i = 0; i < reverseStart.length; i++) {
            String read = "read_" + (i + 1);
            lengths.put(read, 101);
            reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 11));
            reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, reverseStart[i]));
        }

        List<ReadCall> products = TestEvidence.evaluate(reads, lengths, PCR_PRIMERS, DEFAULTS).products("assay");

        assertEquals(1, products.size(), "70, 71 and 80 bp are within " + ProductSizes.GAP + " bp of a neighbour");
        assertEquals(Set.of(70, 71, 80), products.getFirst().sizes());
        assertEquals("70,71,80", products.getFirst().sizeSummary());
        assertTrue(products.getFirst().matches(60) && products.getFirst().matches(90));
        assertTrue(!products.getFirst().matches(59) && !products.getFirst().matches(91));
    }

    /** The probe site with its first base changed: one mismatch against {@link TestHits#PRIMER_20}. */
    private static String oneMismatch() {
        return (PRIMER_20.charAt(0) == 'A' ? 'C' : 'A') + PRIMER_20.substring(1);
    }

    private static ReportRow representativeRow(String exactVariant, String otherVariant, List<String> readsOfExact) {
        PrimerPanel primers = PrimerPanel.of(Map.of("assay-F", PRIMER_20, "assay-R", PRIMER_20,
                exactVariant, PRIMER_20, otherVariant, PRIMER_20));
        Sample reads = reads();
        for (String read : List.of("read_1", "read_2")) {
            reads.hits().add("assay-F", TestHits.plusHit("assay-F", read, 11));
            reads.hits().add("assay-R", TestHits.minusHit("assay-R", read, 61));
            reads.hits().add(otherVariant, TestHits.plusHit(otherVariant, read, 35, oneMismatch(), PRIMER_20));
            if (readsOfExact.contains(read)) {
                reads.hits().add(exactVariant, TestHits.plusHit(exactVariant, read, 35));
            }
        }
        ReadEvidence evidence = TestEvidence.evaluate(reads, Map.of("read_1", 101, "read_2", 101), primers,
                new AmpliconOptions(0, 1, 5, 0, 5, 0, 5000));
        return evidence.products("assay").getFirst().row();
    }

    @Test
    void ofTheVariantsOfADegenerateProbeTheOneThatMatchesBestIsReported() {
        // Both variants hit both reads, one exactly and one with a mismatch. Whatever their
        // names and the order they are met in, the row shows the exact one.
        for (String[] names : new String[][] {{"assay-P_0", "assay-P_1"}, {"assay-P_1", "assay-P_0"}}) {
            ReportRow row = representativeRow(names[0], names[1], List.of("read_1", "read_2"));

            assertEquals(names[0], row.probeId());
            assertEquals(0, row.probeMismatches());
            assertEquals("read_1", row.contig());
        }
    }

    @Test
    void aReadWhereTheProbeMatchesExactlyIsTheRepresentative() {
        // read_1 comes first by name, but only read_2 carries the allele one variant matches exactly.
        ReportRow row = representativeRow("assay-P_1", "assay-P_0", List.of("read_2"));

        assertEquals("read_2", row.contig());
        assertEquals("assay-P_1", row.probeId());
        assertEquals(0, row.probeMismatches());
    }

    @Test
    void aRowWithoutProbeColumnsHasNoProbeMismatch() {
        Sample reads = reads();
        for (String read : List.of("read_1", "read_2")) {
            reads.hits().add("plain-F", TestHits.plusHit("plain-F", read, 11));
            reads.hits().add("plain-R", TestHits.minusHit("plain-R", read, 61));
        }
        ReadEvidence evidence = TestEvidence.evaluate(reads, Map.of("read_1", 101, "read_2", 101),
                PrimerPanel.of(Map.of("plain-F", PRIMER_20, "plain-R", PRIMER_20)), DEFAULTS);
        ReportRow row = evidence.products("plain").getFirst().row();

        assertEquals("", row.probeId());
        assertEquals(0, row.probeMismatches());
        assertEquals("read_1", row.contig());
    }

    @Test
    void ofTwoPrimerVariantsThatBindAlikeTheSameOneIsAlwaysShown() {
        // Both variants of a degenerate forward primer hit the read with no mismatch (the
        // site lies in bases BLAST left out, say). The row must not depend on the order
        // the hits are met in, which changes from one run to the next.
        PrimerPanel primers = PrimerPanel.of(Map.of("deg-F_0", PRIMER_20, "deg-F_1", PRIMER_20, "deg-R", PRIMER_20));
        for (List<String> order : List.of(List.of("deg-F_0", "deg-F_1"), List.of("deg-F_1", "deg-F_0"))) {
            Sample reads = reads();
            for (String read : List.of("read_1", "read_2")) {
                for (String forward : order) {
                    reads.hits().add(forward, TestHits.plusHit(forward, read, 11));
                }
                reads.hits().add("deg-R", TestHits.minusHit("deg-R", read, 61));
            }
            ReadEvidence evidence = TestEvidence.evaluate(reads, Map.of("read_1", 101, "read_2", 101), primers,
                    DEFAULTS);
            ReportRow row = evidence.products("deg").getFirst().row();

            assertEquals("deg-F_0", row.forwardPrimer());
            assertEquals("read_1", row.contig());
        }
    }

    @Test
    void aProductCoversItsSizesGiveOrTakeALengthVariant() {
        // The one rule for calls from reads and from extended reads alike.
        assertEquals(true, ProductSizes.sameProduct(List.of(300, 310, 318), 328));
        assertEquals(true, ProductSizes.sameProduct(Set.of(300), 290));
        assertEquals(false, ProductSizes.sameProduct(List.of(300, 310, 318), 329));
        assertEquals(false, ProductSizes.sameProduct(Set.of(300), 289));
    }

    @Test
    void aPrimerEndingOnAReadsLastBaseIsContainedInIt() {
        // A read (or merged pair) ending exactly at the product's end holds the whole product.
        Hits reads = new Hits();
        reads.add("assay-R", TestHits.minusHit("assay-R", "read_1", 81)); // 81-100 of a 100-base read
        reads.add("assay-F", TestHits.plusHit("assay-F", "read_1", 81));
        Hits contained = ReadLevelCaller.withContainedHits(reads, Map.of("read_1", 100));
        assertEquals(2, contained.of(List.of("assay-F", "assay-R")).size());
        assertEquals(0, ReadLevelCaller.withContainedHits(reads, Map.of("read_1", 99)).of(List.of("assay-F")).size());
    }
}
