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
        assertEquals("No product for 2 assay-sample pair(s) (1 assay(s)); a product reported under another assay of the"
                + " reaction (mix=) for 1 assay-sample pair(s) (1 assay(s)): r_no_product.tsv says why",
                ReportGenerator.noProductLine(rows, "r_no_product.tsv"));
        assertEquals("A product reported under another assay of the reaction (mix=) for 1 assay-sample pair(s)"
                + " (1 assay(s)): r_no_product.tsv says which", ReportGenerator.noProductLine(List.of(row("s1", "b",
                merged)), "r_no_product.tsv"));
    }
}
