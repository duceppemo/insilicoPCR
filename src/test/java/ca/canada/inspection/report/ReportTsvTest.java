package ca.canada.inspection.report;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.pcr.TestRows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReportTsvTest {

    @TempDir
    Path dir;

    @Test
    void rowsWrittenToAReportReadBackUnchanged() {
        Sample sample = new Sample();
        sample.setName("S");
        ReportRow withProbe = new ReportRow("S", "qpcr", 101, 220, 120, "contig_1,len=900", "a description",
                "qpcr-F", "qpcr-R_1", 1, 0, 1, 0, '-', new ReportRow.Probe("qpcr-P", 140, 163, 2), 3, 0);
        ReportRow withoutProbe = TestRows.row("S", "plain", 11, 80, "read_7", "FASTQ read-level call: ...");
        // A probe in the panel gives the report its probe columns, empty for the plain assay.
        PrimerPanel primers = PrimerPanel.of(Map.of("qpcr-F", "ACGT", "qpcr-R", "ACGT", "qpcr-P", "ACGT",
                "plain-F", "ACGT", "plain-R", "ACGT"));

        Path report = ConsolidatedReport.write(dir, Map.of("S", sample),
                PrimerPanel.of(primers.sequences(), ExpectedSizes.fromHeaders(Map.of("qpcr-F", "size=120"))),
                AmpliconOptions.withMismatches(0), Map.of("S", List.of(withProbe, withoutProbe)));

        assertEquals(List.of(withProbe, withoutProbe), ReportTsv.read(report));
    }

    @Test
    void aTabInADescriptionIsWrittenAsASpaceAndTheColumnsStayInPlace() {
        Sample sample = new Sample();
        sample.setName("S");
        ReportRow row = TestRows.row("S", "plain", 11, 80, "ctg1", "len=5000\tcov=12.3");

        Path report = ConsolidatedReport.write(dir, Map.of("S", sample),
                PrimerPanel.of(Map.of("plain-F", "ACGT", "plain-R", "ACGT")), AmpliconOptions.withMismatches(0),
                Map.of("S", List.of(row)));

        assertEquals(List.of(TestRows.row("S", "plain", 11, 80, "ctg1", "len=5000 cov=12.3")), ReportTsv.read(report));
    }

    @Test
    void aFileSavedByASpreadsheetReads() throws IOException {
        String header = String.join("\t", ReportColumn.FIRST.stream().map(ReportColumn::header).toList());
        String product = "S\tits\t11-444\t434\tcontig_1\t\tits-F\tits-R\t0\t0\t0\t0\t+";
        // With a byte order mark before the header...
        Path withMark = Files.writeString(dir.resolve("mark.tsv"), "\uFEFF" + header + "\n" + product + "\n");
        // ...and without the header line.
        Path withoutHeader = Files.writeString(dir.resolve("bare.tsv"), product + "\n" + product.replace("S\t", "T\t") + "\n");

        assertEquals(List.of("S"), ReportTsv.read(withMark).stream().map(ReportRow::sample).toList());
        assertEquals(List.of("S", "T"), ReportTsv.read(withoutHeader).stream().map(ReportRow::sample).toList());
        assertEquals(434, ReportTsv.read(withoutHeader).getFirst().size());
    }

    @Test
    void aReportOfAnEarlierReleaseReadsWithTheMissingColumnsEmpty() throws IOException {
        // Before 0.12: no 5' columns; no probe columns without a probe in the panel.
        Path report = Files.write(dir.resolve("report.tsv"), List.of(
                "Sample\tGene\tGenomeLocation\tAmpliconSize\tContig",
                "S\tits\t11-444\t434\tcontig_1",
                "",
                "S\tits\t1-2\tnot a size\tcontig_2"));

        List<ReportRow> rows = ReportTsv.read(report);

        assertEquals(1, rows.size());
        ReportRow row = rows.getFirst();
        assertEquals(List.of("S", "its", "11-444", "434", "contig_1"),
                List.of(row.sample(), row.assay(), row.location(), String.valueOf(row.size()), row.contig()));
        assertNull(row.probe());
        assertEquals(0, row.forwardUnpaired());
    }
}
