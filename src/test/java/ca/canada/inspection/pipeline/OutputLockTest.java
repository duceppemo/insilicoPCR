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

    @Test
    void aRunInAnotherProcessHoldsTheFolderToo() throws Exception {
        // The case that matters (two command-line runs), where the operating system's lock refuses the
        // second; within one program the overlap is found before it asks.
        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        Process other = new ProcessBuilder(javaCommand, "-cp", System.getProperty("java.class.path"),
                LockHolder.class.getName(), dir.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var out = new java.io.BufferedReader(new java.io.InputStreamReader(other.getInputStream()))) {
            assertEquals("locked", out.readLine());
            List<String> warnings = new ArrayList<>();
            PipelineException error = assertThrows(PipelineException.class, () -> OutputLock.take(dir, warnings::add));
            assertTrue(error.getMessage().startsWith("Another run is using the output folder " + dir), error.getMessage());

            other.getOutputStream().close(); // that run ends
            assertTrue(other.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            try (OutputLock mine = OutputLock.take(dir, warnings::add)) {
                assertEquals(List.of(), warnings);
            }
        } finally {
            other.destroyForcibly();
        }
    }
}
