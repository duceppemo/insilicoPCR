package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The external programs are stood in for by the Java runtime running the tests, on every platform. */
class ExternalProgramTest {

    @Test
    void onTheWindowsConsoleAProgramsAccentsAreWrittenInTheConsolesOwnEncoding() throws IOException {
        // The programs print in the computer's code page (1252); cmd.exe shows its own (850): "Résultats" came out
        // as "RÚsultats". Read in the first and written in the second, it is right.
        java.nio.charset.Charset ansi = java.nio.charset.Charset.forName("windows-1252");
        java.nio.charset.Charset oem = java.nio.charset.Charset.forName("IBM850");
        String said = "Writing to C:\\Résultats\\x.fq\n" + BbtoolsCommands.OUT_OF_MEMORY_LINE + "\n";
        ByteArrayOutputStream shown = new ByteArrayOutputStream();

        boolean outOfMemory = ExternalProgram.copyToConsole(new java.io.ByteArrayInputStream(said.getBytes(ansi)), ansi,
                new PrintStream(shown, true, oem));

        assertEquals(said, shown.toString(oem));
        assertTrue(outOfMemory, "the out-of-memory line is still recognised");

        // Elsewhere, byte for byte.
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        ExternalProgram.copyToConsole(new java.io.ByteArrayInputStream(said.getBytes(StandardCharsets.UTF_8)), null,
                new PrintStream(copied, true, StandardCharsets.UTF_8));
        assertArrayEquals(said.getBytes(StandardCharsets.UTF_8), copied.toByteArray());
    }

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
    void aFailedProgramSaysWhereItRan() {
        // Its command may name files relative to that folder: run again by hand, from there.
        PipelineException error = assertThrows(PipelineException.class, () -> ExternalProgram.run(new Recorder(),
                directory, List.of(JAVA, "-XX:+NoSuchOptionAtAll"), "java"));
        assertTrue(error.getMessage().endsWith(" (in " + directory + ")"), error.getMessage());
    }

    /** The command that runs a one-class Java program with this {@code main} body. */
    private List<String> program(String name, String main) throws IOException {
        Path source = directory.resolve(name + ".java");
        Files.writeString(source, "class " + name + " {\n public static void main(String[] args) throws Exception {\n"
                + main + "\n }\n}\n");
        return List.of(JAVA, source.toString());
    }

    /** A program that prints BBTools' out-of-memory message and stops as BBTools then does. */
    private List<String> bbtoolsOutOfMemory() throws IOException {
        return program("Stop", """
                System.err.println("java.lang.OutOfMemoryError");
                System.err.println();
                System.err.println("%s");
                System.exit(1);
                """.formatted(BbtoolsCommands.OUT_OF_MEMORY_LINE));
    }

    /** Runs on the console with standard error captured; what was printed. */
    private static byte[] onTheConsole(List<String> command, Class<? extends Throwable> expected) {
        PrintStream console = System.err;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(printed, true, StandardCharsets.UTF_8));
            if (expected == null) {
                ExternalProgram.run(RunMonitor.CONSOLE, null, command, "program");
            } else {
                assertThrows(expected, () -> ExternalProgram.run(RunMonitor.CONSOLE, null, command, "program"));
            }
        } finally {
            System.setErr(console);
        }
        return printed.toByteArray();
    }

    @Test
    void onTheConsoleTheErrorOutputIsPassedOnByteForByte() throws IOException {
        // A carriage return and a byte that is not UTF-8 come out as they went in.
        List<String> command = program("Bytes", """
                System.err.write(new byte[] {'5', '%', '\\r', '9', (byte) 0xE9, '\\n'});
                System.err.flush();
                """);

        byte[] said = {'5', '%', '\r', '9', (byte) 0xE9, '\n'};
        // On Windows, read in the code page the programs print in and written in the console's encoding (here the
        // test's UTF-8): 0xE9 is é there.
        byte[] shown = AppPaths.isWindows()
                ? new String(said, ExternalProgram.OUTPUT_ENCODING).getBytes(StandardCharsets.UTF_8) : said;
        assertArrayEquals(shown, onTheConsole(command, null));
    }

    @Test
    void theMessageIsRecognisedWithOtherOutputOnItsLine() throws IOException {
        // With standard output merged in, another thread's unfinished line may come first.
        Recorder monitor = new Recorder();
        List<String> command = program("Joined", """
                System.out.print("Loading 40%%");
                System.out.flush();
                System.err.println("%s");
                System.exit(1);
                """.formatted(BbtoolsCommands.OUT_OF_MEMORY_LINE));

        ProgramFailedException error = assertThrows(ProgramFailedException.class,
                () -> ExternalProgram.run(monitor, null, command, "Tadpole"));

        assertTrue(error.saidOutOfMemory(), monitor.lines.toString());
    }

    @Test
    void anInterruptStopsAProgramOnTheConsole() throws Exception {
        List<String> command = program("Sleep", "Thread.sleep(120_000);");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread running = new Thread(() -> {
            try {
                ExternalProgram.run(RunMonitor.CONSOLE, null, command, "Sleep");
            } catch (Throwable e) {
                thrown.set(e);
            }
        });

        running.start();
        Thread.sleep(1000);
        running.interrupt();
        running.join(20_000);

        assertFalse(running.isAlive(), "still waiting for the program");
        // A cancellation (the application interrupts the run thread to stop the run), not a
        // failed program that a step could note and go on after.
        assertTrue(thrown.get() instanceof CancellationException
                && thrown.get().getMessage().equals("The run was cancelled (Sleep)."), String.valueOf(thrown.get()));
    }

    @Test
    void aProgramSayingItRanOutOfMemoryIsRecognised() throws IOException {
        Recorder monitor = new Recorder();

        ProgramFailedException error = assertThrows(ProgramFailedException.class,
                () -> ExternalProgram.run(monitor, null, bbtoolsOutOfMemory(), "Tadpole"));

        assertEquals(1, error.exitCode());
        assertTrue(error.saidOutOfMemory());
        assertTrue(monitor.lines.contains(BbtoolsCommands.OUT_OF_MEMORY_LINE), monitor.lines.toString());
    }

    @Test
    void onTheConsoleTheErrorOutputIsPassedOnAndReadToo() throws IOException {
        List<String> command = bbtoolsOutOfMemory();
        AtomicReference<ProgramFailedException> error = new AtomicReference<>();
        PrintStream console = System.err;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(printed, true, StandardCharsets.UTF_8));
            error.set(assertThrows(ProgramFailedException.class,
                    () -> ExternalProgram.run(RunMonitor.CONSOLE, null, command, "Tadpole")));
        } finally {
            System.setErr(console);
        }

        assertTrue(error.get().saidOutOfMemory());
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains(BbtoolsCommands.OUT_OF_MEMORY_LINE),
                printed.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anOrdinaryFailureIsNotRunningOutOfMemory() {
        ProgramFailedException error = assertThrows(ProgramFailedException.class,
                () -> ExternalProgram.run(new Recorder(), null, List.of(JAVA, "-XX:+NoSuchOptionAtAll"), "java"));

        assertFalse(error.saidOutOfMemory());
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

    @Test
    void aProgramEndedBySigintOrSigtermMayBeTheRunsOwnStop() {
        // Ctrl-C reaches the programs before this program's shutdown marks the run stopped.
        boolean windows = AppPaths.isWindows();
        org.junit.jupiter.api.Assertions.assertEquals(!windows, ExternalProgram.killedBySignal(130));
        org.junit.jupiter.api.Assertions.assertEquals(!windows, ExternalProgram.killedBySignal(143));
        org.junit.jupiter.api.Assertions.assertFalse(ExternalProgram.killedBySignal(1));
        org.junit.jupiter.api.Assertions.assertFalse(ExternalProgram.killedBySignal(137)); // SIGKILL: not a stop to wait for
    }
}
