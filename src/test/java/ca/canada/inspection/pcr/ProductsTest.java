package ca.canada.inspection.pcr;

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
