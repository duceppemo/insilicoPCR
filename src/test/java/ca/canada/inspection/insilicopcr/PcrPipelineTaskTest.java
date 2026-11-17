package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.pipeline.OutputFolderInUse;
import ca.canada.inspection.pipeline.PipelineException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcrPipelineTaskTest {

    @Test
    void theLogAreaKeepsItsLatestLinesWhole() {
        // A large FASTQ run's lines slowed the window: the area keeps the latest, cut at a line start.
        String text = "line one\nline two\nline three\n";

        assertEquals(0, PcrPipelineTask.cutPoint(text, text.length(), 10), "within the limit");
        int cut = PcrPipelineTask.cutPoint(text, 20, 14);
        assertEquals("line three\n", text.substring(cut));
    }

    @Test
    void aRunRefusedByTheLockWritesNoLogIntoTheOtherRunsFolder(@TempDir Path dir) {
        // The refused run's lines went into log.txt of the folder another run was writing.
        assertFalse(PcrPipelineTask.writesRunLog(dir, new OutputFolderInUse(dir)));
        assertTrue(PcrPipelineTask.writesRunLog(dir, null));
        assertTrue(PcrPipelineTask.writesRunLog(dir, new PipelineException("a failure of its own")));
        assertFalse(PcrPipelineTask.writesRunLog(dir.resolve("never made"), null), "no folder made");
    }
}
