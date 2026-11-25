package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.NoProduct;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The QA log's line on the assays without a product. */
class ReportGeneratorTest {

    private static NoProduct row(String sample, String assay, String reason) {
        return new NoProduct(sample, assay, "", reason, "", Double.NaN, 55);
    }

    @Test
    void anAssayWhoseProductIsReportedUnderAnotherIsNotCountedAsWithoutOne() {
        String merged = "its product is reported under a: the two assays share a reaction (mix=) and make it at the"
                + " same place";
        List<NoProduct> rows = List.of(row("s1", "b", merged), row("s1", "c", "no site: BLAST found no match"),
                row("s1", "c", "no site: BLAST found no match"), row("s2", "c", "no site: BLAST found no match"));
        assertEquals("2 assay(s) gave no product in a sample; 1 assay(s) made a product reported under another assay"
                + " of their reaction (mix=): r_no_product.tsv says why", ReportGenerator.noProductLine(rows,
                "r_no_product.tsv"));
        assertEquals("1 assay(s) made a product reported under another assay of their reaction (mix=):"
                + " r_no_product.tsv says which", ReportGenerator.noProductLine(List.of(row("s1", "b", merged)),
                "r_no_product.tsv"));
    }
}
