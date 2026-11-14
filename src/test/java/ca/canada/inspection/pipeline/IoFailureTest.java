package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.BlastReports;
import ca.canada.inspection.report.ConsolidatedReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Sample sample = new Sample();
        sample.setName("s");

        RuntimeException error = assertThrows(RuntimeException.class, () -> ConsolidatedReport.write(notAFolder,
                Map.of("s", sample), PrimerPanel.of(Map.of("a-F", "ACGT", "a-R", "ACGT")), AmpliconOptions.withMismatches(0)));

        assertTrue(RunFailure.isForTheUser(error), error.toString());
        assertTrue(RunFailure.lines(error).getFirst().startsWith("Error: Unable to write consolidated report: "),
                RunFailure.lines(error).toString());
    }

    @Test
    void blastReportsThatCannotBeListedAreAMessage() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> BlastReports.attachHits(
                dir.resolve("no-such-folder"), Map.of(), PrimerPanel.EMPTY, AmpliconOptions.withMismatches(0)));

        assertTrue(RunFailure.isForTheUser(error), error.toString());
        assertEquals("Error: Unable to list BLAST reports in: " + dir.resolve("no-such-folder"),
                RunFailure.lines(error).getFirst());
        assertFalse(RunFailure.lines(error).isEmpty());
    }
}
