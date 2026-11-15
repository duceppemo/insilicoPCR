package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputLockTest {

    @TempDir
    Path dir;

    @Test
    void aSecondRunIntoAFolderInUseIsRefusedAndTheFolderIsFreeAfterTheFirst() {
        // A workflow manager running a job per sample into one folder: they overwrote each other.
        List<String> warnings = new ArrayList<>();
        try (OutputLock first = OutputLock.take(dir, warnings::add)) {
            PipelineException error = assertThrows(PipelineException.class, () -> OutputLock.take(dir, warnings::add));
            assertTrue(error.getMessage().startsWith("Another run is using the output folder " + dir), error.getMessage());
            assertTrue(RunFailure.isForTheUser(error));
        }
        try (OutputLock again = OutputLock.take(dir, warnings::add)) {
            assertEquals(List.of(), warnings);
        }
    }
}
