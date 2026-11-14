package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The external programs are stood in for by the Java runtime running the tests, on every platform. */
class ExternalProgramTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

    @TempDir
    Path directory;

    /** Collects what programs print, and can be cancelled. */
    private static final class Recorder implements RunMonitor {
        final List<String> lines = new ArrayList<>();
        final ExternalProcessTracker tracker = new ExternalProcessTracker();
        final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public Consumer<String> programOutput() {
            return lines::add;
        }

        @Override
        public ExternalProcessTracker processes() {
            return tracker;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    @Test
    void whatTheProgramPrintsGoesToTheMonitor() {
        Recorder monitor = new Recorder();

        ExternalProgram.run(monitor, directory, List.of(JAVA, "-version"), "java");

        assertTrue(monitor.lines.stream().anyMatch(line -> line.contains("version")), monitor.lines.toString());
    }

    @Test
    void aProgramOnTheConsoleRunsToo() {
        assertDoesNotThrow(() -> ExternalProgram.run(RunMonitor.CONSOLE, null, List.of(JAVA, "-version"), "java"));
    }

    @Test
    void aProgramThatFailsIsAFailureForTheUserWithItsCommandLine() {
        List<String> command = List.of(JAVA, "-XX:+NoSuchOptionAtAll");

        PipelineException error = assertThrows(PipelineException.class,
                () -> ExternalProgram.run(new Recorder(), null, command, "java"));

        assertTrue(error.getMessage().startsWith("java failed with exit code 1: " + JAVA), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void aProgramThatCannotBeStartedIsAFailureForTheUser() {
        Path missing = directory.resolve("makeblastdb");

        PipelineException error = assertThrows(PipelineException.class,
                () -> ExternalProgram.run(new Recorder(), null, List.of(missing.toString(), "-in", "x"), "makeblastdb"));

        assertEquals("Could not run makeblastdb: " + missing + " -in x", error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void aCancelledRunStartsNoProgram() {
        Recorder monitor = new Recorder();
        monitor.cancelled.set(true);

        assertThrows(CancellationException.class,
                () -> ExternalProgram.run(monitor, null, List.of(JAVA, "-version"), "java"));
        assertTrue(monitor.lines.isEmpty());
    }

    @Test
    void aProgramStartedAfterTheWindowClosedIsKilled() {
        // The tracker kills it on registration; it is then reported as a failure, not as success.
        Recorder monitor = new Recorder();
        monitor.tracker.destroyAll();

        assertThrows(PipelineException.class,
                () -> ExternalProgram.run(monitor, null, List.of(JAVA, "-version"), "java"));
        assertFalse(monitor.isCancelled());
    }
}
