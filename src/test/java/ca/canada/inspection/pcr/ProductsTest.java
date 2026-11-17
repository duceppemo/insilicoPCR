package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.Sample;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static ca.canada.inspection.pcr.TestHits.minusHit;
import static ca.canada.inspection.pcr.TestHits.plusHit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductsTest {

    /** A degenerate forward primer expanded into twelve variants, each hitting the same site. */
    private static PrimerPanel degenerate() {
        Map<String, String> sequences = new LinkedHashMap<>();
        for (int i = 1; i <= 12; i++) {
            sequences.put("toxA-F_" + i, PRIMER_20);
        }
        sequences.put("toxA-R", PRIMER_20);
        return PrimerPanel.of(sequences);
    }

    @Test
    void aProductOfSeveralVariantsIsOneRowWhateverOrderTheHitsCameIn() {
        // Twelve variants make the same product: one row, the same one every run.
        Sample sample = new Sample("S");
        for (int i = 12; i >= 1; i--) {
            sample.hits().add("toxA-F_" + i, plusHit("toxA-F_" + i, "c1", 101));
        }
        sample.hits().add("toxA-R", minusHit("toxA-R", "c1", 581));

        List<String> forward = Products.of(sample, degenerate(), AmpliconOptions.withMismatches(0)).stream()
                .map(ReportRow::forwardPrimer).toList();

        assertEquals(List.of("toxA-F_1"), forward);
    }

    @Test
    void theRowOfAProductNamesTheVariantsThatBindBest() {
        // toxA-F_1 binds with a mismatch, toxA-F_2 perfectly: the row names toxA-F_2. A product
        // elsewhere on the contig keeps its own row.
        Sample sample = new Sample("S");
        String mismatched = "ACGTTCGTACGTACGTACGT"; // a mismatch at base 5, outside the 3' window
        sample.hits().add("toxA-F_1", plusHit("toxA-F_1", "c1", 101, mismatched, PRIMER_20));
        sample.hits().add("toxA-F_2", plusHit("toxA-F_2", "c1", 101));
        sample.hits().add("toxA-F_2", plusHit("toxA-F_2", "c1", 1101));
        sample.hits().add("toxA-R", minusHit("toxA-R", "c1", 581));
        sample.hits().add("toxA-R", minusHit("toxA-R", "c1", 1581));

        List<ReportRow> rows = Products.of(sample, degenerate(),
                AmpliconOptions.builder().mismatches(1).maxLength(1000).build());

        assertEquals(List.of("toxA-F_2 0 500", "toxA-F_2 0 500"), rows.stream()
                .map(row -> row.forwardPrimer() + " " + row.forwardMismatches() + " " + row.size()).toList());
        assertEquals(List.of(101, 1101), rows.stream().map(ReportRow::start).toList());
    }

    @Test
    void aVariantWhose5PrimeBaseDoesNotPairDoesNotStandForThePerfectOne() {
        // blastn-short leaves a mismatch at a primer's first bases out of the alignment: toxA-F_1
        // aligns from its base 2 (base 1 unpaired), toxA-F_2 over all 20. Both have no mismatch in
        // the alignment and make one product; the row names the one that pairs over its length.
        Sample sample = new Sample("S");
        sample.hits().add("toxA-F_1", TestHits.truncatedPlusHit("toxA-F_1", "c1", 102, 2, 20, PRIMER_20));
        sample.hits().add("toxA-F_2", plusHit("toxA-F_2", "c1", 101));
        sample.hits().add("toxA-R", minusHit("toxA-R", "c1", 581));

        List<ReportRow> rows = Products.of(sample, degenerate(), AmpliconOptions.withMismatches(0));

        assertEquals(List.of("toxA-F_2 0 500"), rows.stream()
                .map(row -> row.forwardPrimer() + " " + row.forwardUnpaired() + " " + row.size()).toList());
    }

    @Test
    void aPrimerAloneOnBothStrandsMakesAProductOfItsAssay() {
        // An inverted repeat: toxA-F binds the plus strand at 101 and the minus strand at 581, facing.
        Sample sample = new Sample("S");
        sample.hits().add("toxA-F_1", plusHit("toxA-F_1", "c1", 101));
        sample.hits().add("toxA-F_1", minusHit("toxA-F_1", "c1", 581));

        List<ReportRow> rows = Products.of(sample, degenerate(), AmpliconOptions.withMismatches(0));

        // Found once, from its plus-strand site, not again with the two hits swapped.
        assertEquals(List.of("toxA toxA-F_1 toxA-F_1 500 + ONE_PRIMER"), rows.stream().map(row -> row.assay() + " "
                + row.forwardPrimer() + " " + row.reversePrimer() + " " + row.size() + " " + row.strand() + " "
                + row.pairing()).toList());
    }

    @Test
    void aReversePrimerAloneOnBothStrandsMakesAProductOfItsAssayToo() {
        Sample sample = new Sample("S");
        sample.hits().add("toxA-R", plusHit("toxA-R", "c1", 101));
        sample.hits().add("toxA-R", minusHit("toxA-R", "c1", 581));

        List<ReportRow> rows = Products.of(sample, degenerate(), AmpliconOptions.withMismatches(0));

        assertEquals(List.of("toxA toxA-R toxA-R 500 ONE_PRIMER"), rows.stream().map(row -> row.assay() + " "
                + row.forwardPrimer() + " " + row.reversePrimer() + " " + row.size() + " " + row.pairing()).toList());
    }

    @Test
    void aOnePrimerProductOfAnAssayWithAProbeNeedsTheProbeInsideIt() {
        // As for its designed product: in a qPCR tube, a product the probe does not bind gives no signal.
        Map<String, String> sequences = Map.of("q-F", PRIMER_20, "q-R", PRIMER_20, "q-P", PRIMER_20);
        AmpliconOptions options = AmpliconOptions.withMismatches(0);
        java.util.function.IntFunction<List<String>> withProbeAt = at -> {
            Sample sample = new Sample("S");
            sample.hits().add("q-F", plusHit("q-F", "c1", 101));
            sample.hits().add("q-F", minusHit("q-F", "c1", 581));
            sample.hits().add("q-P", plusHit("q-P", "c1", at));
            return Products.of(sample, PrimerPanel.of(sequences), options).stream()
                    .map(row -> row.assay() + " " + row.size() + " " + row.pairing()).toList();
        };

        assertEquals(List.of("q 500 ONE_PRIMER"), withProbeAt.apply(301));
        assertEquals(List.of(), withProbeAt.apply(2_001));
    }

    @Test
    void ofTwoEquallyBoundProbeSitesInAProductTheFirstFromItsStartIsShown() {
        // BLAST listed the site at 450 first: the row showed it, not the one at 301 before it.
        Map<String, String> sequences = Map.of("q-F", PRIMER_20, "q-R", PRIMER_20, "q-P", PRIMER_20);
        Sample sample = new Sample("S");
        sample.hits().add("q-F", plusHit("q-F", "c1", 101));
        sample.hits().add("q-R", minusHit("q-R", "c1", 581));
        sample.hits().add("q-P", plusHit("q-P", "c1", 450));
        sample.hits().add("q-P", plusHit("q-P", "c1", 301));

        List<ReportRow> rows = Products.of(sample, PrimerPanel.of(sequences), AmpliconOptions.withMismatches(0));

        assertEquals(List.of("101-600 probe 301-320"), rows.stream().map(row -> row.start() + "-" + row.end()
                + " probe " + row.probe().start() + "-" + row.probe().end()).toList());
    }

    @Test
    void twoForwardPrimersOfAssaysInOneReactionMakeAProductOfBoth() {
        // a-F on the plus strand, b-F on the minus strand facing it: a product of the two assays' mix.
        Sample sample = new Sample("S");
        sample.hits().add("a-F", plusHit("a-F", "c1", 101));
        sample.hits().add("b-F", minusHit("b-F", "c1", 581));
        Map<String, String> sequences = new LinkedHashMap<>();
        for (String id : List.of("a-F", "a-R", "b-F", "b-R")) {
            sequences.put(id, PRIMER_20);
        }
        MixGroups mix = MixGroups.fromHeaders(Map.of("a-F", "mix=panel1", "b-F", "mix=panel1"));

        List<ReportRow> rows = Products.of(sample, PrimerPanel.of(sequences, ExpectedSizes.NONE, mix),
                AmpliconOptions.withMismatches(0));

        assertEquals(List.of("a+b a-F b-F 500 TWO_ASSAYS"), rows.stream().map(row -> row.assay() + " "
                + row.forwardPrimer() + " " + row.reversePrimer() + " " + row.size() + " " + row.pairing()).toList());
    }

    @Test
    void primersOfTwoAssaysPairOnlyWhenTheyShareAReaction() {
        Sample sample = new Sample("S");
        sample.hits().add("a-F", plusHit("a-F", "c1", 101));
        sample.hits().add("b-R", minusHit("b-R", "c1", 581));
        Map<String, String> sequences = new LinkedHashMap<>();
        for (String id : List.of("a-F", "a-R", "b-F", "b-R")) {
            sequences.put(id, PRIMER_20);
        }
        AmpliconOptions options = AmpliconOptions.withMismatches(0);

        // Run on their own (no mix=): no product of a-F with b-R.
        assertEquals(List.of(), Products.of(sample, PrimerPanel.of(sequences), options));

        MixGroups mix = MixGroups.fromHeaders(Map.of("a-F", "mix=panel1", "b-R", "size=300 mix=panel1"));
        List<ReportRow> rows = Products.of(sample, PrimerPanel.of(sequences, ExpectedSizes.NONE, mix), options);
        assertEquals(List.of("a+b a-F b-R 500 TWO_ASSAYS"), rows.stream().map(row -> row.assay() + " "
                + row.forwardPrimer() + " " + row.reversePrimer() + " " + row.size() + " " + row.pairing()).toList());
    }

    @Test
    void aPrimerSharedByTwoAssaysOfOneReactionMakesEachMoleculeOnce() {
        // AMOS-like: abortus-F and meli-F are one primer, repeated under each assay's ID, at an inverted
        // repeat (101 on the plus strand, 900 on the minus); meli-R makes meli's designed 500 bp product.
        Sample sample = new Sample("S");
        for (String id : List.of("abortus-F", "meli-F")) {
            sample.hits().add(id, plusHit(id, "c1", 101));
            sample.hits().add(id, minusHit(id, "c1", 900));
        }
        sample.hits().add("meli-R", minusHit("meli-R", "c1", 581));
        Map<String, String> sequences = new LinkedHashMap<>();
        for (String id : List.of("abortus-F", "abortus-R", "meli-F", "meli-R")) {
            sequences.put(id, PRIMER_20);
        }
        java.util.function.Function<List<ReportRow>, List<String>> shown = rows -> rows.stream()
                .map(row -> row.assay() + " " + row.start() + "-" + row.end() + " " + row.pairing()).toList();
        AmpliconOptions options = AmpliconOptions.withMismatches(0);

        // One reaction: the 819 bp molecule once (one primer, the first assay by name), the 500 bp once
        // (meli's designed product, not again as abortus+meli).
        MixGroups amos = MixGroups.fromHeaders(Map.of("abortus-F", "mix=amos", "meli-F", "mix=amos"));
        assertEquals(List.of("abortus 101-919 ONE_PRIMER", "meli 101-600 DESIGNED"),
                shown.apply(Products.of(sample, PrimerPanel.of(sequences, ExpectedSizes.NONE, amos), options)));

        // Run apart, each assay is its own tube: each reports what its primers make.
        assertEquals(List.of("abortus 101-919 ONE_PRIMER", "meli 101-600 DESIGNED", "meli 101-919 ONE_PRIMER"),
                shown.apply(Products.of(sample, PrimerPanel.of(sequences), options)));
    }

    @Test
    void pairingNearbyHitsOnlyFindsEveryProductAllPairsFind() {
        // Since 0.15.3 a primer's hit is compared only with its partner's hits within the longest product of
        // it, not with every hit on the contig: the products must be the same, in the same order, those of
        // exactly the longest length included. Hits spread over two contigs, some 1,999 to 2,001 bp apart.
        java.util.Random random = new java.util.Random(11);
        Sample sample = new Sample("S");
        List<BlastResult> forward = new java.util.ArrayList<>();
        List<BlastResult> reverse = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String contig = random.nextBoolean() ? "c1" : "c2";
            int at = 1 + random.nextInt(200_000);
            BlastResult f = random.nextBoolean() ? plusHit("a-F", contig, at) : minusHit("a-F", contig, at);
            BlastResult r = random.nextBoolean() ? minusHit("a-R", contig, at + 1_980 + random.nextInt(3))
                    : minusHit("a-R", contig, 1 + random.nextInt(200_000));
            forward.add(f);
            reverse.add(r);
        }
        forward.forEach(hit -> sample.hits().add("a-F", hit));
        reverse.forEach(hit -> sample.hits().add("a-R", hit));
        AmpliconOptions options = AmpliconOptions.builder().mismatches(0).maxLength(2_000).build();
        List<String> everyPair = new java.util.ArrayList<>();
        for (BlastResult f : forward) {
            for (BlastResult r : reverse) {
                AmpliconCaller.call(f, r, options).ifPresent(product ->
                        everyPair.add(product.contig() + " " + product.start() + "-" + product.end()));
            }
        }

        List<String> found = Products.of(sample, PrimerPanel.of(Map.of("a-F", PRIMER_20, "a-R", PRIMER_20)), options)
                .stream().filter(row -> row.pairing() == ReportRow.Pairing.DESIGNED)
                .map(row -> row.site().contig() + " " + row.start() + "-" + row.end()).toList();

        assertEquals(everyPair, found);
        assertTrue(everyPair.stream().anyMatch(product -> {
            String[] ends = product.substring(3).split("-");
            return Integer.parseInt(ends[1]) - Integer.parseInt(ends[0]) + 1 == 2_000;
        }), "a product of exactly the longest length");
    }

    @Test
    void aSamplesProductsAreWorkedOutOnceUntilItsHitsChange() {
        Sample sample = new Sample("S");
        sample.hits().add("toxA-F_1", plusHit("toxA-F_1", "c1", 101));
        sample.hits().add("toxA-R", minusHit("toxA-R", "c1", 581));
        PrimerPanel primers = degenerate();
        AmpliconOptions options = AmpliconOptions.builder().maxLength(1000).build();

        List<ReportRow> first = Products.of(sample, primers, options);
        assertSame(first, Products.of(sample, primers, options));
        sample.hits().add("toxA-F_2", plusHit("toxA-F_2", "c1", 1101));
        sample.hits().add("toxA-R", minusHit("toxA-R", "c1", 1581));
        assertEquals(2, Products.of(sample, primers, options).size(), "a new product once the hits change");
    }

    @Test
    void productsOfDifferentSizesAtAContigStartKeepTheirOwnRows() {
        // Both hang off the contig start, so both locations are clamped to 1: sizes 203 and 207.
        Map<String, String> sequences = new LinkedHashMap<>();
        sequences.put("B-F_1", PRIMER_20);
        sequences.put("B-F_2", PRIMER_20 + "ACGT");
        sequences.put("B-R", PRIMER_20);
        Sample sample = new Sample("S");
        // Aligned from base 5 (of 20) and base 9 (of 24) on: 4 and 8 bases hang off the contig start.
        sample.hits().add("B-F_1", TestHits.truncatedPlusHit("B-F_1", "c1", 1, 5, 20, PRIMER_20));
        sample.hits().add("B-F_2", TestHits.truncatedPlusHit("B-F_2", "c1", 1, 9, 24, PRIMER_20 + "ACGT"));
        sample.hits().add("B-R", minusHit("B-R", "c1", 180));

        List<ReportRow> rows = Products.of(sample, PrimerPanel.of(sequences),
                AmpliconOptions.builder().fivePrimeTruncation(8).build());

        assertEquals(List.of(203, 207), rows.stream().map(ReportRow::size).sorted().toList(), rows.toString());
    }
}
