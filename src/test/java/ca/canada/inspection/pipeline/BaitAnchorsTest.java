package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.PrimerPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The anchors of unpaired reads, whose search the read-level search keeps (the stand-ins search nothing). */
class BaitAnchorsTest {

    @TempDir
    Path dir;

    private SampleFiles files() throws IOException {
        return new SampleFiles(Files.createDirectories(dir.resolve("s")), "s");
    }

    private PipelineContext context() {
        // No blastn or settings: neither is asked for without a read to search.
        return new PipelineContext(null, null, new PipelineDirectories(dir, dir.resolve("detailed_report"),
                dir.resolve(PipelineDirectories.CONSOLIDATED)), Map.of(), PrimerPanel.of(Map.of()), 1, List.of(),
                RunMonitor.CONSOLE);
    }

    @Test
    void anUnpairedFirstBaitOfNoReadLeavesNoHitsForTheReadLevelSearch() throws IOException {
        SampleFiles files = files();
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(files.targetMatches()))) {
            out.flush();
        }
        // An earlier run's, in a reused output folder: the read-level search would take them for this run's.
        Files.writeString(files.baitedReadsHits(), "stale\n");

        assertEquals(0, BaitAnchors.write(context(), files, false, 1));

        assertFalse(Files.exists(files.baitedReadsHits()));
        assertFalse(Files.exists(files.baitedReadsFasta()));
        assertTrue(Files.isRegularFile(files.baitAnchors()));
    }

    @Test
    void unpairedReadsThatCannotBeReadAreSaidAsPairedOnesAre() throws IOException {
        SampleFiles files = files();
        Files.writeString(files.targetMatches(), "not a gzip stream");

        PipelineException error = assertThrows(PipelineException.class,
                () -> BaitAnchors.write(context(), files, false, 1));

        assertEquals("Could not find the primer sites of the baited reads: " + files.targetMatches(), error.getMessage());
        assertFalse(Files.exists(files.baitedReadsFasta()));
    }
}
