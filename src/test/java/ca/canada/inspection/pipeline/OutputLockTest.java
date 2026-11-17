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
    void aSecondRunRefusedInThisProgramLeavesTheFolderHeldForOtherProcesses() throws Exception {
        // The window: Stop, then Run into the same folder before the first run's thread ended. The second run's
        // channel, closed after its tryLock failed, let go of the first run's lock on Linux: another process got in.
        List<String> warnings = new ArrayList<>();
        try (OutputLock first = OutputLock.take(dir, warnings::add)) {
            assertThrows(OutputFolderInUse.class, () -> OutputLock.take(dir, warnings::add));
            assertThrows(OutputFolderInUse.class, () -> OutputLock.take(dir.resolve("."), warnings::add),
                    "another path to the folder");

            String javaCommand = ProcessHandle.current().info().command().orElse("java");
            Process other = new ProcessBuilder(javaCommand, "-cp", System.getProperty("java.class.path"),
                    LockHolder.class.getName(), dir.toString()).redirectErrorStream(true).start();
            try {
                other.getOutputStream().close();
                String said = new String(other.getInputStream().readAllBytes());
                assertTrue(other.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(!said.startsWith("locked") && said.contains("Another run is using the output folder"), said);
            } finally {
                other.destroyForcibly();
            }
        }
    }

    @Test
    void aSecondChannelThatFindsTheLockHeldHereIsKeptOpen() throws Exception {
        // Through another path to the file than HELD knows (a bind mount, say): the overlap is found by the system's
        // lock, and the channel kept open, as closing it would free the first run's lock on Linux.
        List<String> warnings = new ArrayList<>();
        try (OutputLock first = OutputLock.take(dir, warnings::add)) {
            assertThrows(OutputFolderInUse.class, () -> OutputLock.take(dir, dir.resolve(OutputLock.FILE_NAME),
                    dir.resolve("another key"), warnings::add));
            assertThrows(OutputFolderInUse.class, () -> OutputLock.take(dir, warnings::add), "still held");
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

    @Test
    void aFolderThatCannotBeLockedIsWarnedAboutAndTheRunGoesOn() {
        // A file system that cannot lock (some network shares): the run goes on, the user told the risk.
        // Here the lock file cannot even be made, its folder missing.
        List<String> warnings = new ArrayList<>();

        try (OutputLock none = OutputLock.take(dir.resolve("missing"), warnings::add)) {
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().startsWith("Warning: could not lock the output folder ("), warnings.getFirst());
            assertTrue(warnings.getFirst().endsWith("do not run another run into it at the same time"),
                    warnings.getFirst());
        }
    }
}
