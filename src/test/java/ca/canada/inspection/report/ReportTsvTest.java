package ca.canada.inspection.report;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.Products;
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
        Sample sample = new Sample("S");
        ReportRow withProbe = TestRows.of("S", "qpcr", 101, 220, 120, "contig_1,len=900", "a description",
                "qpcr-F", "qpcr-R_1", 1, 0, 1, 0, '-', new ReportRow.Probe("qpcr-P", 140, 163, 2), 3, 0);
        ReportRow withoutProbe = TestRows.row("S", "plain", 11, 80, "read_7", "FASTQ read-level call: ...");
        // A probe in the panel gives the report its probe columns, empty for the plain assay.
        PrimerPanel primers = PrimerPanel.of(Map.of("qpcr-F", "ACGT", "qpcr-R", "ACGT", "qpcr-P", "ACGT",
                "plain-F", "ACGT", "plain-R", "ACGT"));

        Path report = ConsolidatedReport.write(dir, Products.ofRun(Map.of("S", sample), PrimerPanel.of(primers.sequences(), ExpectedSizes.fromHeaders(Map.of("qpcr-F", "size=120"))), AmpliconOptions.withMismatches(0), Map.of("S", List.of(withProbe, withoutProbe))), PrimerPanel.of(primers.sequences(), ExpectedSizes.fromHeaders(Map.of("qpcr-F", "size=120"))));

        assertEquals(List.of(withProbe, withoutProbe), ReportTsv.read(report));
    }

    @Test
    void aTabInADescriptionIsWrittenAsASpaceAndTheColumnsStayInPlace() {
        Sample sample = new Sample("S");
        ReportRow row = TestRows.row("S", "plain", 11, 80, "ctg1", "len=5000\tcov=12.3");

        Path report = ConsolidatedReport.write(dir, Products.ofRun(Map.of("S", sample), PrimerPanel.of(Map.of("plain-F", "ACGT", "plain-R", "ACGT")), AmpliconOptions.withMismatches(0), Map.of("S", List.of(row))), PrimerPanel.of(Map.of("plain-F", "ACGT", "plain-R", "ACGT")));

        assertEquals(List.of(TestRows.row("S", "plain", 11, 80, "ctg1", "len=5000 cov=12.3")), ReportTsv.read(report));
    }

    @Test
    void aFileSavedByASpreadsheetReads() throws IOException {
        String header = String.join("\t", ReportColumn.ALL.subList(0, ReportColumn.STRAND.ordinal() + 1).stream().map(ReportColumn::header).toList());
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

    @Test
    void theSourceOfACallFromReadsReadsBackFromTheDescriptionItsCallerWrites() {
        // A run keeps where a row was called as data; a report read back from disk has only the
        // description, written by the callers: the two must agree whatever the wording.
        ReportRow representative = TestRows.row("S", "its", 11, 444, "read_7", "read 7 description");
        ReportRow fromReads = ca.canada.inspection.pcr.ReadLevelCaller.reportRow(
                new ca.canada.inspection.pcr.ReadLevelCaller.ReadCall("its", 15, 24, 0, representative,
                        java.util.Set.of(434), 0.3));
        ReportRow extended = ca.canada.inspection.pcr.ReadExtensionCaller.reportRow(
                new ca.canada.inspection.pcr.ReadExtensionCaller.Product("its", 1074, java.util.Set.of(1074), 3, 5, 2, 4,
                        representative, ca.canada.inspection.pcr.PairSupport.Verdict.NOT_CHECKED), "seed read");

        assertEquals(new ReportRow.Source.Reads(15, 24, "63%"), fromReads.source());
        assertEquals(fromReads.source(), ReportTsv.source(fromReads.contigDescription()));
        assertEquals(new ReportRow.Source.Extension(3, 5, 2, 4), extended.source());
        assertEquals(extended.source(), ReportTsv.source(extended.contigDescription()));
        assertEquals(ReportRow.Source.ASSEMBLY, ReportTsv.source("len=9000 cov=12"));
    }

    @Test
    void aBlastReportIsNotReadAsProducts() throws IOException {
        // Next to report.tsv in the output folder; its numbers would read as products of 0 bp.
        Path blast = Files.writeString(dir.resolve("isolate_03.tsv"),
                "isolate_03_contig1\tstxQ-R\t20\t0\t0\t1e-05\t40.1\t20\t20\t101\t120\tACGT\t1\t20\tACGT\n");
        java.io.UncheckedIOException error = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.UncheckedIOException.class, () -> ReportTsv.read(blast));
        assertEquals("Not a consolidated report: " + blast, error.getMessage());
    }

    @Test
    void aHeaderlessLineWithALocationButNoSizeIsNotAReport() throws IOException {
        Path noSize = Files.writeString(dir.resolve("no_size.tsv"), "S\tits\t11-444\tlong\tcontig_1\n");
        java.io.UncheckedIOException error = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.UncheckedIOException.class, () -> ReportTsv.read(noSize));
        assertEquals("Not a consolidated report: " + noSize, error.getMessage());
    }

    @Test
    void anEmptyFileHasNoRowsAndAMissingOneIsAnError() throws IOException {
        assertEquals(List.of(), ReportTsv.read(Files.writeString(dir.resolve("empty.tsv"), "")));

        Path missing = dir.resolve("missing.tsv");
        java.io.UncheckedIOException error = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.UncheckedIOException.class, () -> ReportTsv.read(missing));
        assertEquals("Unable to read consolidated report: " + missing, error.getMessage());
    }

    @Test
    void aHeaderRepeatedByConcatenatedReportsIsSkipped() throws IOException {
        String header = String.join("\t", ReportColumn.ALL.subList(0, ReportColumn.STRAND.ordinal() + 1).stream().map(ReportColumn::header).toList());
        String product = "S\tits\t11-444\t434\tcontig_1\t\tits-F\tits-R\t0\t0\t0\t0\t+";
        Path joined = Files.writeString(dir.resolve("joined.tsv"),
                header + "\n" + product + "\n" + header + "\n" + product.replace("S\t", "T\t") + "\n");
        assertEquals(List.of("S", "T"), ReportTsv.read(joined).stream().map(ReportRow::sample).toList());
    }

    @Test
    void aReportSavedInTheWindowsEncodingReads() throws IOException {
        // Excel's "Text (Tab delimited)": an accented letter is one byte, not UTF-8.
        String text = String.join("\t", ReportColumn.ALL.subList(0, ReportColumn.STRAND.ordinal() + 1).stream().map(ReportColumn::header).toList()) + "\n"
                + "S\tits\t11-444\t434\tcontig_1\tsouche résistante\tits-F\tits-R\t0\t0\t0\t0\t+\n";
        Path report = Files.write(dir.resolve("excel.tsv"), text.getBytes(java.nio.charset.Charset.forName("windows-1252")));
        assertEquals("souche résistante", ReportTsv.read(report).getFirst().contigDescription());
    }

    @Test
    void theSizeCheckOfEachRowReadsBack() throws IOException {
        // As the run writes it when the primer file declares sizes: the last column.
        String header = String.join("\t", ReportColumn.ALL.subList(0, ReportColumn.STRAND.ordinal() + 1).stream().map(ReportColumn::header).toList())
                + "\tSizeCheck";
        Path report = Files.write(dir.resolve("checked.tsv"), List.of(header,
                "S\tits\t11-444\t434\tcontig_1\t\tits-F\tits-R\t0\t0\t0\t0\t+\ttarget",
                "S\tits\t11-611\t601\tcontig_1\t\tits-F\tits-R\t0\t0\t0\t0\t+\tunexpected",
                "S\tplain\t1-200\t200\tcontig_2\t\tp-F\tp-R\t0\t0\t0\t0\t+\t"));

        assertEquals(java.util.Arrays.asList(ExpectedSizes.Label.TARGET, ExpectedSizes.Label.UNEXPECTED, null),
                ReportTsv.readChecked(report).stream().map(ReportTsv.Checked::sizeCheck).toList());
        // Without the column, no row is checked.
        Path plain = Files.write(dir.resolve("plain.tsv"), List.of(header.replace("\tSizeCheck", ""),
                "S\tits\t11-444\t434\tcontig_1\t\tits-F\tits-R\t0\t0\t0\t0\t+"));
        assertNull(ReportTsv.readChecked(plain).getFirst().sizeCheck());
    }

    @Test
    void aReportSavedAsUnicodeTextWithQuotedCellsReads() throws IOException {
        // Excel's "Unicode Text": UTF-16 with a byte order mark; a cell holding a quote or a comma
        // is put between quotes, its own quotes doubled.
        String header = String.join("\t", ReportColumn.ALL.subList(0, ReportColumn.STRAND.ordinal() + 1).stream().map(ReportColumn::header).toList());
        String text = header + "\r\n" + "\"S,1 \"\"a\"\"\"\tits\t11-444\t434\tcontig_1\t\tits-F\tits-R\t0\t0\t0\t0\t+\r\n";
        byte[] utf16 = text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        byte[] withMark = new byte[utf16.length + 2];
        withMark[0] = (byte) 0xff;
        withMark[1] = (byte) 0xfe;
        System.arraycopy(utf16, 0, withMark, 2, utf16.length);
        Path report = Files.write(dir.resolve("unicode.txt"), withMark);

        ReportRow row = ReportTsv.read(report).getFirst();

        assertEquals("S,1 \"a\"", row.sample());
        assertEquals(434, row.size());
    }
}
