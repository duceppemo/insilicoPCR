package ca.canada.inspection.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlastResultTest {

    @Test
    void whereAKeptFivePrimeMismatchLiesIsUnknownWithoutTheAlignedBases() {
        String bases = "ACGTACGTACGTACGTACGT";
        // A mismatch counted free, the aligned bases unknown: no position to give.
        BlastResult unknown = new BlastResult("c", "a-F", 1, 0, 101, 120, 1, 20, 20, 20, null, null, bases, 0, 1);
        assertEquals(Integer.MIN_VALUE, unknown.pairsAgainAfterLastMismatch());
        // One counted free that the alignment does not show (all its bases pair): none either.
        BlastResult none = new BlastResult("c", "a-F", 0, 0, 101, 120, 1, 20, 20, 20, bases, bases, bases, 0, 1);
        assertEquals(Integer.MIN_VALUE, none.pairsAgainAfterLastMismatch());
    }

    @Test
    void aGapAtTheEdgeOfTheFivePrimeAllowanceCountsTheSameOnBothStrands() {
        // A template base between oligo bases 5 and 6, with --5p-truncation 5: free on the plus strand, counted on
        // the minus strand. Counted at the oligo base on its 3' side (6) on both, it counts on both.
        String bases = "ACGTACGTACGTACGTACGT";
        String plusQuery = bases.substring(0, 5) + "G" + bases.substring(5);
        String plusOligo = bases.substring(0, 5) + "-" + bases.substring(5);
        BlastResult plus = new BlastResult("c", "a-F", 0, 1, 101, 121, 1, 20, 20, 21, plusQuery, plusOligo, bases);
        // On the minus strand the alignment runs from oligo base 20 down: the gap comes after 15 oligo bases.
        String minusQuery = bases.substring(0, 15) + "G" + bases.substring(15);
        String minusOligo = bases.substring(0, 15) + "-" + bases.substring(15);
        BlastResult minus = new BlastResult("c", "a-F", 0, 1, 101, 121, 20, 1, 20, 21, minusQuery, minusOligo, bases);

        assertEquals(1, plus.alignedMismatchesPast(5));
        assertEquals(1, minus.alignedMismatchesPast(5));
        assertEquals(0, plus.alignedMismatchesPast(6));
        assertEquals(0, minus.alignedMismatchesPast(6));
    }
}
