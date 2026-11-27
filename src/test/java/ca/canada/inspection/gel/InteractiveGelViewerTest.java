package ca.canada.inspection.gel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading a report for the gel window, which runs off the window's thread and needs no display. */
class InteractiveGelViewerTest {

    @TempDir
    Path dir;

    @Test
    void aReportIsReadIntoTheLanesTheWindowDraws() throws IOException {
        Path report = Files.writeString(dir.resolve("report.tsv"),
                "Sample\tGene\tGenomeLocation\tAmpliconSize\tContig\ns2\ttoxA\t1-312\t312\tc1\n");

        InteractiveGelViewer.Loaded loaded = InteractiveGelViewer.load(report, List.of("s1", "s2"));

        assertEquals(report, loaded.consolidatedReport());
        assertEquals(List.of("s1", "s2"), List.copyOf(loaded.lanes().keySet()));
        assertEquals(312, loaded.lanes().get("s2").getFirst().ampliconSize());
        assertTrue(loaded.lanes().get("s1").isEmpty(), "a lane without a band");
    }

    @Test
    void aReportWithNoSampleToDrawIsRefusedBeforeTheWindowOpens() throws IOException {
        Path report = Files.writeString(dir.resolve("report.tsv"), "Sample\tGene\tGenomeLocation\tAmpliconSize\tContig\n");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> InteractiveGelViewer.load(report, List.of()));
        assertEquals("No sample to draw in consolidated report: " + report, e.getMessage());
    }
}
