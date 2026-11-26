package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.pcr.AmpliconCaller.Amplicon;
import org.junit.jupiter.api.Test;

import java.util.List;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmpliconCallerTest {

    private static final AmpliconOptions DEFAULTS = AmpliconOptions.defaults();

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
        assertEquals("101-600", amplicon.start() + "-" + amplicon.end());
    }

    @Test
    void anIndelInAPrimerSiteIsNotInTheProduct() {
        // The primer is copied into the product, not the site it binds: a base the site has more (forward,
        // 100..120 for 20 primer bases) or fewer (reverse, 581..599) leaves the product 500 bp, the size of
        // the same pair on sites without them. Before 0.15.3 the size was the sites' span, 501 and 499.
        String primer = TestHits.PRIMER_20;
        String withInsertion = primer.substring(0, 10) + "A" + primer.substring(10);
        BlastResult insertion = TestHits.plusHit("assay-F", "contig1", 100, withInsertion,
                primer.substring(0, 10) + "-" + primer.substring(10));
        BlastResult deletion = TestHits.minusHit("assay-R", "contig1", 581,
                primer.substring(0, 10) + "-" + primer.substring(11), primer);
        BlastResult forward = TestHits.plusHit("assay-F", "contig1", 101);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 581);

        Amplicon longerSite = AmpliconCaller.call(insertion, reverse, DEFAULTS).orElseThrow();
        assertEquals(List.of(100, 600, 500), List.of(longerSite.start(), longerSite.end(), longerSite.size()));
        Amplicon shorterSite = AmpliconCaller.call(forward, deletion, DEFAULTS).orElseThrow();
        assertEquals(List.of(101, 599, 500), List.of(shorterSite.start(), shorterSite.end(), shorterSite.size()));
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
        AmpliconOptions unlimited = AmpliconOptions.builder().maxLength(0).build();
        BlastResult forward = TestHits.plusHit("assay-F", "contig1", 1);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 1_999_981);
        assertEquals(2_000_000, AmpliconCaller.call(forward, reverse, unlimited).orElseThrow().size());
    }

    @Test
    void productsShorterThanMinLengthAreRejected() {
        AmpliconOptions options = AmpliconOptions.builder().minLength(100).build();
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
    void primerHangingOffTheContigStartStillCountsInFullTowardTheSize() {
        // The contig starts at primer base 7: six primer bases lie before it. The product
        // is as long as on a complete template (500 bp), although only 494 bp of it are on
        // the contig. A contig that ends inside the other primer is already sized that way.
        BlastResult forward = TestHits.truncatedPlusHit("assay-F", "contig1", 1, 7, 20, TestHits.PRIMER_20);
        BlastResult reverse = TestHits.minusHit("assay-R", "contig1", 475);

        AmpliconCaller.Amplicon amplicon = AmpliconCaller.call(forward, reverse, DEFAULTS).orElseThrow();
        assertEquals(500, amplicon.size());
        assertEquals("1-494", amplicon.start() + "-" + amplicon.end());

        // Both size limits see the full product, not the part on the contig.
        assertTrue(AmpliconCaller.call(forward, reverse, AmpliconOptions.builder().maxLength(499).build()).isEmpty());
        assertTrue(AmpliconCaller.call(forward, reverse, AmpliconOptions.builder().minLength(500).maxLength(500).build()).isPresent());
    }

    @Test
    void probeInsideAmpliconIsContained() {
        Amplicon amplicon = new Amplicon("contig1", 101, 600, 500, '+', 0);
        assertTrue(amplicon.contains(150, 170));
        assertTrue(amplicon.contains(101, 600));
        assertTrue(!amplicon.contains(90, 110));
        assertTrue(!amplicon.contains(590, 610));
    }

    @Test
    void primersOverlappingHeadToHeadFaceEachOtherByTheirFullLength() {
        // Forward at 301-320, its first 4 bases free (within --5p-truncation): BLAST aligns 305-320. The reverse at
        // 303-322 ends inside those free bases. By the aligned bases the pair faced away, and the 22-bp product,
        // which the size counts on the full primers, was dropped.
        String primer = "ACGTACGTACGTACGTACGT";
        BlastResult forward = withFivePrimeUnpaired(TestHits.truncatedPlusHit("a-F", "c1", 305, 5, 20, primer), 0);
        BlastResult reverse = TestHits.minusHit("a-R", "c1", 303);

        Amplicon amplicon = AmpliconCaller.call(forward, reverse, DEFAULTS).orElseThrow();

        assertEquals("301-322", amplicon.start() + "-" + amplicon.end());
        assertEquals(22, amplicon.size());

        // The reverse's 3' end on forward 5' bases that do not pair (or were not compared with the template) sits
        // on a mismatch in the strand the forward made: no product, as before 0.15.3.
        assertTrue(AmpliconCaller.call(withFivePrimeUnpaired(forward, 1), reverse, DEFAULTS).isEmpty());
        assertTrue(AmpliconCaller.call(withFivePrimeUnpaired(forward, -1), reverse, DEFAULTS).isEmpty());
        // A reverse whose 3' end is on the aligned bases faces the forward whatever its 5' bases.
        assertTrue(AmpliconCaller.call(withFivePrimeUnpaired(forward, 1), TestHits.minusHit("a-R", "c1", 305),
                DEFAULTS).isPresent());
    }

    @Test
    void aFivePrimeMismatchBlastKeptAlignedStopsTheOtherPrimersThreePrimeEndAsATrimmedOneDoes() {
        // Forward at 301-320 with a mismatch at its 5th base (305), within --5p-truncation: BLAST keeps it aligned.
        // A reverse whose 3' end is on it was reported (0.15.4), though its 3' end would sit on a mismatch in the
        // strand the forward made; as when BLAST leaves the base out, it is not.
        String template = "ACGTGCGTACGTACGTACGT";
        BlastResult forward = TestHits.plusHit("a-F", "c1", 301, template, TestHits.PRIMER_20).withFivePrimeFree(5);
        assertEquals(1, forward.fivePrimeFree());
        assertEquals(306, forward.pairsAgainAfterLastMismatch());

        assertTrue(AmpliconCaller.call(forward, TestHits.minusHit("a-R", "c1", 305), DEFAULTS).isEmpty(), "on it");
        assertTrue(AmpliconCaller.call(forward, TestHits.minusHit("a-R", "c1", 303), DEFAULTS).isEmpty(), "before it");
        assertEquals(25, AmpliconCaller.call(forward, TestHits.minusHit("a-R", "c1", 306), DEFAULTS).orElseThrow().size(),
                "past it");

        // The mirror image on the minus strand: the reverse at 303-322, its 5' end at 322, a mismatch at its 5th base
        // (318); a forward ending on it (299-318) gives no product, one ending at 317 does.
        String reverseTemplate = "ACGTACGTACGTACGCACGT"; // query orientation: its 5th base from the right differs
        BlastResult reverse = TestHits.minusHit("a-R", "c1", 303, reverseTemplate, TestHits.PRIMER_20).withFivePrimeFree(5);
        assertEquals(317, reverse.pairsAgainAfterLastMismatch());
        assertTrue(AmpliconCaller.call(TestHits.plusHit("a-F", "c1", 299), reverse, DEFAULTS).isEmpty());
        assertTrue(AmpliconCaller.call(TestHits.plusHit("a-F", "c1", 298), reverse, DEFAULTS).isPresent());
    }

    @Test
    void anyBaseOfAPrimerThatDoesNotPairStopsTheOtherPrimersThreePrimeEnd() {
        // A mismatch at the forward's 10th base, past its 5 free 5' bases: a reverse ending on it was reported
        // (0.15.4), its 3' end on a base the strand the forward made does not pair with.
        AmpliconOptions oneMismatch = DEFAULTS;
        String template = "ACGTACGTAAGTACGTACGT"; // base 10 (310) differs
        BlastResult forward = TestHits.plusHit("a-F", "c1", 301, template, TestHits.PRIMER_20).withFivePrimeFree(5);
        assertEquals(0, forward.fivePrimeFree());
        assertEquals(311, forward.pairsAgainAfterLastMismatch());
        assertTrue(AmpliconCaller.call(forward, TestHits.minusHit("a-R", "c1", 310), oneMismatch).isEmpty(), "on it");
        assertTrue(AmpliconCaller.call(forward, TestHits.minusHit("a-R", "c1", 305), oneMismatch).isEmpty(), "before it");
        assertTrue(AmpliconCaller.call(forward, TestHits.minusHit("a-R", "c1", 311), oneMismatch).isPresent(), "past it");
        // A product that does not overlap is not concerned.
        assertTrue(AmpliconCaller.call(forward, TestHits.minusHit("a-R", "c1", 500), oneMismatch).isPresent());

        // A 3' base left out (unpaired): no primer may overlap it at all; one just past its end still faces it.
        BlastResult cut = TestHits.truncatedPlusHit("a-F", "c1", 301, 1, 19, TestHits.PRIMER_20);
        assertEquals(1, cut.threePrimeUncovered());
        AmpliconOptions lenient = AmpliconOptions.builder().threePrimePenalty(3).build();
        assertTrue(AmpliconCaller.call(cut, TestHits.minusHit("a-R", "c1", 315), lenient).isEmpty());
        assertTrue(AmpliconCaller.call(cut, TestHits.minusHit("a-R", "c1", 321), lenient).isPresent());
    }

    @Test
    void aPrimerWhoseKeptFivePrimeMismatchCannotBePlacedLetsNoPrimerOverlapIt() {
        // The aligned bases unknown, where its 5' mismatch lies is too: the other primer may not reach into it at all.
        BlastResult forward = new BlastResult("c1", "a-F", 1, 0, 301, 320, 1, 20, 20, 20, null, null,
                TestHits.PRIMER_20, 0, 1);
        assertTrue(AmpliconCaller.call(forward, TestHits.minusHit("a-R", "c1", 310), DEFAULTS).isEmpty());
        assertTrue(AmpliconCaller.call(forward, TestHits.minusHit("a-R", "c1", 321), DEFAULTS).isPresent());
    }

    @Test
    void minusPrimerFacesByItsFivePrimeBasesOnlyWhereTheyPair() {
        // The mirror image: reverse at 303-322 (minus strand), its 5' bases 319-322 left out by BLAST; the forward
        // at 301-320 ends on them.
        String primer = "ACGTACGTACGTACGTACGT";
        BlastResult full = TestHits.minusHit("a-R", "c1", 303);
        BlastResult reverse = new BlastResult(full.queryID(), full.subjectID(), 0, 0, 303, 318, 20, 5, 20, 16,
                full.qseq().substring(0, 16), full.sseq().substring(0, 16), primer, 0);
        BlastResult forward = TestHits.plusHit("a-F", "c1", 301);

        assertEquals(22, AmpliconCaller.call(forward, reverse, DEFAULTS).orElseThrow().size());
        assertTrue(AmpliconCaller.call(forward, withFivePrimeUnpaired(reverse, 2), DEFAULTS).isEmpty());
    }

    private static BlastResult withFivePrimeUnpaired(BlastResult hit, int unpaired) {
        return new BlastResult(hit.queryID(), hit.subjectID(), hit.mismatch(), hit.gaps(), hit.qstart(), hit.qend(),
                hit.sstart(), hit.send(), hit.slen(), hit.length(), hit.qseq(), hit.sseq(), hit.primerSeq(), unpaired,
                hit.fivePrimeFree());
    }

    @Test
    void onACircleAPairAcrossTheOriginAmplifies() {
        // An 1,800-bp circle: forward at 1,701..1,720 (+), reverse at 81..100 (-). On the sequence as written
        // they point off its two ends; round the origin, they make 1,701..1,800 and 1..100: 200 bp.
        BlastResult forward = TestHits.plusHit("assay-F", "plasmid", 1701);
        BlastResult reverse = TestHits.minusHit("assay-R", "plasmid", 81);

        assertTrue(AmpliconCaller.call(forward, reverse, DEFAULTS).isEmpty(), "a linear sequence has no product");
        Amplicon amplicon = AmpliconCaller.call(forward, reverse, DEFAULTS, 1800).orElseThrow();

        assertEquals("1701-100", amplicon.start() + "-" + amplicon.end());
        assertEquals(200, amplicon.size());
        assertTrue(amplicon.roundTheOrigin());
        // A probe on either side of the origin, or across it, is within the product; one outside it is not.
        assertTrue(amplicon.contains(1750, 1770));
        assertTrue(amplicon.contains(20, 40));
        assertTrue(amplicon.contains(1790, 1810));
        assertTrue(!amplicon.contains(500, 520));
        // The size limits see the whole product.
        assertTrue(AmpliconCaller.call(forward, reverse, AmpliconOptions.builder().maxLength(199)
                .build(), 1800).isEmpty());
    }

    @Test
    void onACircleAPairThatWouldGoRoundMoreThanOnceDoesNot() {
        // Forward past the reverse on a 300-bp circle, products longer than the circle: none.
        BlastResult forward = TestHits.plusHit("assay-F", "loop", 101);
        BlastResult reverse = TestHits.minusHit("assay-R", "loop", 581);
        assertTrue(AmpliconCaller.call(forward, reverse, DEFAULTS, 300).isEmpty());
        // And a convergent pair on a circle is the same product as on a linear sequence.
        assertEquals("101-600", AmpliconCaller.call(forward, reverse, DEFAULTS, 2000).map(a -> a.start() + "-"
                + a.end()).orElseThrow());
    }
    @Test
    void sizeLimitsAreInclusiveAtTheBoundaries() {
        // 500 bp product: accepted when max == 500 and when min == 500.
        BlastResult forward = TestHits.plusHit("assay-F", "c", 101);
        BlastResult reverse = TestHits.minusHit("assay-R", "c", 581);
        assertEquals(500, AmpliconCaller.call(forward, reverse,
                AmpliconOptions.builder().minLength(500).maxLength(500).build()).orElseThrow().size());
        assertTrue(AmpliconCaller.call(forward, reverse,
                AmpliconOptions.builder().minLength(501).build()).isEmpty());
        assertTrue(AmpliconCaller.call(forward, reverse,
                AmpliconOptions.builder().maxLength(499).build()).isEmpty());
    }

    @Test
    void crossedPrimersDoNotAmplify() {
        // Minus-strand primer entirely upstream of the plus-strand primer.
        BlastResult forward = TestHits.plusHit("assay-F", "c", 300);
        BlastResult reverse = TestHits.minusHit("assay-R", "c", 250);
        assertTrue(AmpliconCaller.call(forward, reverse, DEFAULTS).isEmpty());
    }

    @Test
    void aMinusStrandPrimerInsideThePlusStrandOneDoesNotAmplify() {
        // Same start, but the minus-strand 10-mer (101..110) ends before the plus-strand 20-mer
        // (101..120): its 3' end points out of the plus-strand primer, not toward its 3' end.
        BlastResult forward = TestHits.plusHit("assay-F", "c", 101);
        BlastResult inside = TestHits.minusHit("assay-R", "c", 101, "ACGTACGTAC", "ACGTACGTAC");
        assertTrue(AmpliconCaller.call(forward, inside, DEFAULTS).isEmpty());

        // Ending where the plus-strand primer ends, it does: the two primers overlap fully.
        BlastResult sameEnd = TestHits.minusHit("assay-R", "c", 111, "ACGTACGTAC", "ACGTACGTAC");
        assertEquals(20, AmpliconCaller.call(forward, sameEnd, DEFAULTS).orElseThrow().size());
    }
}
