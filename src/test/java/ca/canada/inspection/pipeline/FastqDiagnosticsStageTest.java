package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.FastqDiagnostics;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics.Status;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastqDiagnosticsStageTest {

    @Test
    void everyStatusHasItsLinesInTheLog() {
        // The two logs name each status by hand: a status added to FastqDiagnostics and not here was left out of
        // QAlog.txt, which then said that no negative assay had its oligos in the reads.
        for (Status status : Status.values()) {
            Finding finding = new Finding("S", "a", status, FastqDiagnostics.Assembly.NOT_ASSEMBLED,
                    new FastqDiagnostics.ReadsWithOligo(1, 1, 0), 150, "detail", "");
            List<String> lines = new FastqDiagnosticsStage.Result(Path.of("diagnostics.tsv"), List.of(finding), List.of())
                    .qaLogLines();

            assertTrue(lines.size() >= 2, status + ": " + lines);
            assertFalse(lines.stream().anyMatch(line -> line.contains("no negative assay had its oligos")),
                    status + ": " + lines);
        }
    }
}
