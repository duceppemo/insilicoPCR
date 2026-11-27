package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.Products;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.report.ConsolidatedReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A file that cannot be read or written is the user's to fix: an error message, not a stack trace. */
class IoFailureTest {

    @TempDir
    Path dir;

    @Test
    void aReportThatCannotBeWrittenIsAMessage() throws IOException {
        // The folder for the report is a file: as when the disk is full or the folder is read-only.
        Path notAFolder = Files.writeString(dir.resolve("consolidated_report"), "");
        Sample sample = new Sample("s");

        RuntimeException error = assertThrows(RuntimeException.class, () -> ConsolidatedReport.write(notAFolder, Products.ofRun(Map.of("s", sample), PrimerPanel.of(Map.of("a-F", "ACGT", "a-R", "ACGT")), AmpliconOptions.defaults(), Map.of()), PrimerPanel.of(Map.of("a-F", "ACGT", "a-R", "ACGT"))));

        assertTrue(RunFailure.isForTheUser(error), error.toString());
        assertTrue(RunFailure.lines(error).getFirst().startsWith("Error: Unable to write consolidated report: "),
                RunFailure.lines(error).toString());
    }

    @Test
    void aReportCutShortIsNotLeftToTakeTheNextRunsNumber() {
        // A failure once the report has lines in it (a full disk): the file stayed, named by no QAlog, and the next
        // run wrote report(1).tsv.
        PrimerPanel primers = PrimerPanel.of(Map.of("a-F", "ACGT", "a-R", "ACGT"));
        ca.canada.inspection.pcr.ReportRow row = new ca.canada.inspection.pcr.ReportRow("s", "a",
                new ca.canada.inspection.pcr.ReportRow.Site(1, 100, 100, "c1", "", '+'),
                new ca.canada.inspection.pcr.ReportRow.Binding("a-F", 0, 0, 0, 60.0),
                new ca.canada.inspection.pcr.ReportRow.Binding("a-R", 0, 0, 0, 60.0), null,
                ca.canada.inspection.pcr.ReportRow.Source.ASSEMBLY);
        List<ca.canada.inspection.pcr.ReportRow> failing = new java.util.AbstractList<>() {
            @Override
            public ca.canada.inspection.pcr.ReportRow get(int index) {
                if (index > 0) {
                    throw new java.io.UncheckedIOException(new IOException("No space left on device (stand-in)"));
                }
                return row;
            }

            @Override
            public int size() {
                return 3;
            }
        };

        assertThrows(java.io.UncheckedIOException.class, () -> ConsolidatedReport.write(dir, failing, primers));
        assertTrue(Files.notExists(dir.resolve("report.tsv")));
        assertEquals(dir.resolve("report.tsv"), ConsolidatedReport.write(dir, List.of(row), primers));
    }

    @Test
    void aMissingBlastReportIsAMessage() {
        Sample sample = new Sample("s");

        RuntimeException error = assertThrows(RuntimeException.class, () -> ca.canada.inspection.pcr.BlastHits.read(
                SampleFiles.of(dir, "s").blastReport(), sample.files(), sample.hits(), PrimerPanel.of(Map.of()),
                AmpliconOptions.defaults()));

        assertTrue(RunFailure.isForTheUser(error), error.toString());
        assertEquals("Error: Unable to read BLAST report: " + dir.resolve("s").resolve("s.tsv"),
                RunFailure.lines(error).getFirst());
    }
}
