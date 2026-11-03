package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import org.junit.jupiter.api.Test;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static ca.canada.inspection.pcr.TestHits.PRIMER_GC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastResultThreePrimeTest {

    @Test
    void perfectHitHasNoThreePrimeIssues() {
        BlastResult hit = TestHits.plusHit("assay-F", "contig1", 100);
        assertTrue(hit.isPlusStrand());
        assertEquals(0, hit.threePrimeUncovered());
        assertEquals(0, hit.fivePrimeUncovered());
        assertEquals(0, hit.threePrimeMismatches(5));
        assertEquals(0, hit.threePrimePenalty(5));
    }

    @Test
    void minusStrandHitIsDetected() {
        BlastResult hit = TestHits.minusHit("assay-R", "contig1", 500);
        assertFalse(hit.isPlusStrand());
        assertEquals(0, hit.threePrimeUncovered());
        assertEquals(0, hit.threePrimePenalty(5));
    }

    @Test
    void mismatchInsideWindowIsCountedOnPlusStrand() {
        // Mismatch at primer position 18 of 20 -> distance 2 from the 3' terminus.
        String sseq = PRIMER_20;
        String qseq = mutate(sseq, 17); // 0-based index 17 = position 18
        BlastResult hit = TestHits.plusHit("assay-F", "contig1", 100, qseq, sseq);

        assertEquals(1, hit.threePrimeMismatches(5));
        assertEquals(0, hit.threePrimeMismatches(2)); // window of 2 excludes position 18
        // Position 18 of ACGTACGT... is 'C': positional weight 2 + G/C bonus 1.
        assertEquals(3, hit.threePrimePenalty(5));
    }

    @Test
    void mismatchInsideWindowIsCountedOnMinusStrand() {
        // Walk on the minus strand goes from primer 3' (sstart = slen) to 5'.
        // Mutating alignment column 2 mutates primer position 18 (distance 2).
        String sseq = PRIMER_20;
        String qseq = mutate(sseq, 2);
        BlastResult hit = TestHits.minusHit("assay-R", "contig1", 100, qseq, sseq);

        assertEquals(1, hit.threePrimeMismatches(5));
        assertEquals(0, hit.threePrimeMismatches(2));
    }

    @Test
    void gcMismatchNearTerminusIsPenalizedMore() {
        // Mismatch on the terminal base: T-ending primer scores 3, C-ending scores 4.
        BlastResult atEnd = TestHits.plusHit("assay-F", "c", 1, mutate(PRIMER_20, 19), PRIMER_20);
        BlastResult gcEnd = TestHits.plusHit("assay-F", "c", 1, mutate(PRIMER_GC, 19), PRIMER_GC);

        assertEquals(3, atEnd.threePrimePenalty(5));
        assertEquals(4, gcEnd.threePrimePenalty(5));
    }

    @Test
    void mismatchOutsideWindowScoresOne() {
        // Distance 4 from the terminus: weight 1, no G/C bonus even for G/C bases.
        String qseq = mutate(PRIMER_20, 15); // position 16, distance 4
        BlastResult hit = TestHits.plusHit("assay-F", "c", 1, qseq, PRIMER_20);
        assertEquals(1, hit.threePrimePenalty(5));
        assertEquals(1, hit.threePrimeMismatches(5));
    }

    @Test
    void unalignedThreePrimeBasesCountAsMismatches() {
        // BLAST trimmed the terminal base (likely a terminal mismatch): subject covers 1..19 of 20.
        BlastResult hit = TestHits.truncatedPlusHit("assay-F", "c", 1, 1, 19, PRIMER_20);
        assertEquals(1, hit.threePrimeUncovered());
        assertEquals(1, hit.threePrimeMismatches(5));
        assertEquals(3, hit.threePrimePenalty(5)); // terminal T: positional 3
    }

    @Test
    void unalignedGcTerminusGetsBonusPenalty() {
        BlastResult hit = TestHits.truncatedPlusHit("assay-F", "c", 1, 1, 19, PRIMER_GC);
        assertEquals(4, hit.threePrimePenalty(5)); // terminal C: positional 3 + 1
    }

    @Test
    void fivePrimeTruncationIsSeparateFromThreePrime() {
        // Subject covers 3..20: two bases missing at the 5' end, 3' end intact.
        BlastResult hit = TestHits.truncatedPlusHit("assay-F", "c", 1, 3, 20, PRIMER_20);
        assertEquals(2, hit.fivePrimeUncovered());
        assertEquals(0, hit.threePrimeUncovered());
        assertEquals(0, hit.threePrimePenalty(5));
    }

    @Test
    void deletionInQueryInsideWindowIsPenalizedAsIndel() {
        // Query is missing the base opposite primer position 18 ('C'): gap column.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(17, '-');
        BlastResult hit = TestHits.plusHit("assay-F", "c", 1, qseq.toString(), PRIMER_20);
        assertEquals(1, hit.threePrimeMismatches(5));
        assertEquals(4, hit.threePrimePenalty(5)); // positional 2 + G/C bonus 1 + indel 1
    }

    @Test
    void insertionInQueryInsideWindowIsPenalized() {
        // Extra query base between primer positions 18 and 19.
        String sseq = PRIMER_20.substring(0, 18) + "-" + PRIMER_20.substring(18);
        String qseq = PRIMER_20.substring(0, 18) + "A" + PRIMER_20.substring(18);
        BlastResult hit = TestHits.plusHit("assay-F", "c", 1, qseq, sseq);
        assertEquals(1, hit.threePrimeMismatches(5));
        assertTrue(hit.threePrimePenalty(5) >= 3); // positional (near terminus) + indel bonus
    }

    private static String mutate(String seq, int index) {
        char base = Character.toUpperCase(seq.charAt(index));
        char replacement = switch (base) {
            case 'A' -> 'G';
            case 'G' -> 'A';
            case 'C' -> 'T';
            default -> 'C';
        };
        StringBuilder sb = new StringBuilder(seq);
        sb.setCharAt(index, replacement);
        return sb.toString();
    }
    @Test
    void edgeWindowsAndLegacyAliasesBehave() {
        BlastResult hit = TestHits.plusHit("assay-F", "contig1", 100);
        // Zero or negative window: nothing to check.
        assertEquals(0, hit.threePrimeMismatches(0));
        assertEquals(0, hit.threePrimePenalty(-1));
        // Window larger than the primer: clamps to the whole primer.
        assertEquals(0, hit.threePrimeMismatches(50));
        // Legacy accessors used by older code paths.
        assertEquals(hit.qstart(), hit.getStart());
        assertEquals(hit.qend(), hit.getEnd());
        assertEquals(hit.sseq(), hit.getSeq());
        assertEquals("assay-F", hit.getSubjectID());
        assertEquals("contig1", hit.getQueryID());
        assertEquals("sample", hit.getName());
        assertEquals(0, hit.getMismatch());
        assertEquals(20, hit.getLength());
    }

    @Test
    void mismatchedAlignmentStringsFallBackToUncoveredCountOnly() {
        // qseq/sseq length mismatch (defensive): only the unaligned 3' bases count.
        BlastResult hit = new BlastResult("s", "c", "assay-F", 1, 0, 101, 118, 1, 18,
                20, 18, "ACGT", "ACGTACGT", TestHits.PRIMER_20);
        assertEquals(2, hit.threePrimeMismatches(5));
        assertTrue(hit.threePrimePenalty(5) >= 5); // terminal T (3) + position -1 G (2+1)
    }

    @Test
    void minusStrandUncoveredEndsAreOrientedCorrectly() {
        // Minus-strand hit covering primer bases 3..18 of 20: sstart=18, send=3.
        String aligned = TestHits.PRIMER_20.substring(2, 18);
        BlastResult hit = new BlastResult("s", "c", "assay-R", 0, 0, 101, 116, 18, 3,
                20, 16, aligned, aligned, TestHits.PRIMER_20);
        assertFalse(hit.isPlusStrand());
        assertEquals(2, hit.threePrimeUncovered());
        assertEquals(2, hit.fivePrimeUncovered());
    }

}
