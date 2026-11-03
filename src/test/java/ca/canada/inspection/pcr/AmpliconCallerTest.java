package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.pcr.AmpliconCaller.Amplicon;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmpliconCallerTest {

    private static final AmpliconOptions DEFAULTS = AmpliconOptions.withMismatches(0);

    @Test
    void convergentPairOnOppositeStrandsAmplifies() {
        // Forward primer at 101..120 (+), reverse primer at 581..600 (-): product 101..600.
        BlastResult forward = TestHits.plusHit("assay-F", "contig1", 101);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 581);

        Amplicon amplicon = AmpliconCaller.call(forward, reverse, DEFAULTS).orElseThrow();
        assertEquals(101, amplicon.start());
        assertEquals(600, amplicon.end());
        assertEquals(500, amplicon.size());
        assertEquals('+', amplicon.forwardStrand());
        assertEquals("101-600", amplicon.location());
    }

    @Test
    void assayDesignedOnReverseStrandAlsoAmplifies() {
        // The forward primer may legitimately match the minus strand with the
        // reverse primer upstream on the plus strand.
        BlastResult forward = TestHits.minusHit("assay-F", "contig1", 581);
        BlastResult reverse = TestHits.plusHit("assay-R", "contig1", 101);

        Amplicon amplicon = AmpliconCaller.call(forward, reverse, DEFAULTS).orElseThrow();
        assertEquals(500, amplicon.size());
        assertEquals('-', amplicon.forwardStrand());
    }

    @Test
    void differentContigsDoNotAmplify() {
        BlastResult forward = TestHits.plusHit("assay-F", "contig1", 101);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig2", 581);
        assertTrue(AmpliconCaller.call(forward, reverse, DEFAULTS).isEmpty());
    }

    @Test
    void sameStrandPairDoesNotAmplify() {
        BlastResult forward = TestHits.plusHit("assay-F", "contig1", 101);
        BlastResult reverse = TestHits.plusHit("assay-R", "contig1", 581);
        assertTrue(AmpliconCaller.call(forward, reverse, DEFAULTS).isEmpty());
    }

    @Test
    void divergentPairDoesNotAmplify() {
        // Primers point away from each other: minus-strand primer upstream of plus-strand primer.
        BlastResult forward = TestHits.plusHit("assay-F", "contig1", 581);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 101);
        assertTrue(AmpliconCaller.call(forward, reverse, DEFAULTS).isEmpty());
    }

    @Test
    void productsLongerThanMaxLengthAreRejected() {
        BlastResult forward = TestHits.plusHit("assay-F", "contig1", 1);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 49_981); // 50 kb product
        assertTrue(AmpliconCaller.call(forward, reverse, DEFAULTS).isEmpty());
    }

    @Test
    void maxLengthZeroDisablesTheLimit() {
        AmpliconOptions unlimited = new AmpliconOptions(0, 0, 5, 0, 2, 0, 0);
        BlastResult forward = TestHits.plusHit("assay-F", "contig1", 1);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 1_999_981);
        assertEquals(2_000_000, AmpliconCaller.call(forward, reverse, unlimited).orElseThrow().size());
    }

    @Test
    void productsShorterThanMinLengthAreRejected() {
        AmpliconOptions options = new AmpliconOptions(0, 0, 5, 0, 2, 100, 5000);
        BlastResult forward = TestHits.plusHit("assay-F", "contig1", 101);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 131); // 50 bp product
        assertTrue(AmpliconCaller.call(forward, reverse, options).isEmpty());
    }

    @Test
    void overlappingPrimersStillAmplify() {
        // 30 bp product from two 20-mers: primers overlap by 10 bases.
        BlastResult forward = TestHits.plusHit("assay-F", "contig1", 101);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 111);
        assertEquals(30, AmpliconCaller.call(forward, reverse, DEFAULTS).orElseThrow().size());
    }

    @Test
    void fivePrimeTruncatedHitsExtendToTheFullPrimerFootprint() {
        // Forward alignment covers primer 3..20 (2 bp unaligned at the 5' end),
        // starting at template position 103: the real product still starts at 101
        // because the whole primer is incorporated into the amplicon.
        BlastResult forward = TestHits.truncatedPlusHit("assay-F", "contig1", 103, 3, 20, TestHits.PRIMER_20);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 581);

        AmpliconCaller.Amplicon amplicon = AmpliconCaller.call(forward, reverse, DEFAULTS).orElseThrow();
        assertEquals(101, amplicon.start());
        assertEquals(600, amplicon.end());
        assertEquals(500, amplicon.size());
    }

    @Test
    void fivePrimeExtensionIsClampedAtTheContigStart() {
        BlastResult forward = TestHits.truncatedPlusHit("assay-F", "contig1", 2, 3, 20, TestHits.PRIMER_20);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 481);

        assertEquals(1, AmpliconCaller.call(forward, reverse, DEFAULTS).orElseThrow().start());
    }

    @Test
    void probeInsideAmpliconIsContained() {
        Amplicon amplicon = new Amplicon("contig1", 101, 600, '+');
        assertTrue(amplicon.contains(150, 170));
        assertTrue(amplicon.contains(101, 600));
        assertTrue(!amplicon.contains(90, 110));
        assertTrue(!amplicon.contains(590, 610));
    }
}
