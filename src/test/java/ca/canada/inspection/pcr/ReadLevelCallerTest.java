package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadCall;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        ReadCall call = evidence.calls().get("assay");

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

        ReadCall call = ReadLevelCaller.evaluate(reads, lengths, PRIMERS, DEFAULTS).calls().get("assay");

        assertEquals(2, call.supportingReads());
        assertEquals(10, call.informativeReads());
        assertTrue(!call.isReportable());

        // The same 20% support passes a lower --read-min-fraction.
        ReadCall lenient = ReadLevelCaller.evaluate(reads, lengths, PRIMERS, DEFAULTS, 0.2).calls().get("assay");
        assertTrue(lenient.isReportable());
        assertEquals("at least 2 reads and 20% of informative reads", lenient.thresholdSummary());
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
                PRIMERS, DEFAULTS, ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION).calls().get("assay");
        assertEquals(1, paired.supportingReads());
        assertEquals(1, paired.informativeReads());
        assertTrue(!paired.isReportable());

        ReadCall separate = ReadLevelCaller.evaluate(reads, lengths, PRIMERS, DEFAULTS).calls().get("assay");
        assertEquals(2, separate.supportingReads());
        assertTrue(separate.isReportable());
    }

    @Test
    void singleSupportingReadIsNotReportable() {
        Sample reads = reads();
        reads.addBlastResult("assay-F", TestHits.plusHit("assay-F", "read_1", 11));
        reads.addBlastResult("assay-R", TestHits.minusHit("assay-R", "read_1", 61));
        reads.addBlastResult("assay-P", TestHits.plusHit("assay-P", "read_1", 35));

        ReadCall call = ReadLevelCaller.evaluate(reads, Map.of("read_1", 101), PRIMERS, DEFAULTS).calls().get("assay");

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
}
