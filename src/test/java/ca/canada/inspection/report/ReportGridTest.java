package ca.canada.inspection.report;

import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.pcr.TestHits;
import ca.canada.inspection.pcr.TestRows;
import ca.canada.inspection.pcr.SplitAcrossContigs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTML report's grid, before any HTML: its samples, columns, cells and counts. */
class ReportGridTest {

    private static final String PRIMER = "ACGTACGTACGTACGTACGT";

    @Test
    void theGridHasEverySampleAndAssayAndCountsItsCells() {
        PrimerPanel primers = PrimerPanel.of(Map.of("a-F", PRIMER, "a-R", PRIMER, "b-F", PRIMER, "b-R", PRIMER));
        List<ReportRow> rows = List.of(TestRows.row("s1", "a", 1, 300, "c1", ""),
                TestRows.row("s3", "a+b", 1, 200, "c2", "")); // a product of two assays, and a sample not named
        SplitAcrossContigs.Split split = new SplitAcrossContigs.Split("s2", "b", TestHits.plusHit("b-F", "c3", 981), 0,
                TestHits.minusHit("b-R", "c4", 1), 0, 40, false, false);

        ReportGrid grid = ReportGrid.of(HtmlReport.products(rows, primers), List.of("s1", "s2"),
                List.copyOf(primers.assays().keySet()), primers.expectedSizes(), List.of(), List.of(split));

        assertEquals(List.of("s1", "s2", "s3"), List.copyOf(grid.samples()));
        assertEquals(List.of("a", "a+b", "b"), List.copyOf(grid.assays()));
        assertEquals(2, grid.assayCount(), "the column of a product of two assays is not an assay");
        assertEquals(9, grid.totals().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(HtmlReport.State.ACROSS_ENDS, grid.cells().get("s2").get("b").state());
        assertEquals(1, grid.total(HtmlReport.State.ACROSS_ENDS));
        assertTrue(grid.anyUndeclared() && !grid.anyDeclared());
    }

    @Test
    void thePageListsTheAssaysWhoseProductCouldSpanABreak(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        PrimerPanel primers = PrimerPanel.of(Map.of("b-F", PRIMER, "b-R", PRIMER));
        SplitAcrossContigs.Split split = new SplitAcrossContigs.Split("s2", "b", TestHits.plusHit("b-F", "c3", 981), 0,
                TestHits.minusHit("b-R", "c4", 1), 0, 40, false, false);

        String page = java.nio.file.Files.readString(HtmlReport.write(new HtmlReport.Input(
                java.nio.file.Files.writeString(dir.resolve("report.tsv"), ""), List.of(), List.of("s2"),
                java.util.Set.of(), primers, List.of(), new RunDetails("2026-11-16 12:00", "9.9.9", List.of()),
                List.of(), List.of(split))));

        assertTrue(page.contains("<h2>Primers at contig ends (FASTA input)</h2>"), "its section");
        assertTrue(page.contains("<td>s2</td><td>b</td><td class=\"num\">40 bp</td>"), page);
    }
}
