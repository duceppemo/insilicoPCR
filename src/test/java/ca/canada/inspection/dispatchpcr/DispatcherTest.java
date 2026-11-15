package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.pipeline.AppPaths;
import ca.canada.inspection.util.Version;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DispatcherTest {

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Dispatcher.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void versionIsPrinted() {
        assertEquals(0, run("--version"));
        assertEquals("insilicoPCR " + Version.VERSION, out().strip());
    }

    @Test
    void helpListsTheOptions() {
        assertEquals(0, run("-h"));
        assertTrue(out().startsWith("usage: java -jar insilicoPCR.jar -i <input> -o <output> -p <primers>"), out());
        assertTrue(out().contains("--input-check <full|quick>"), out());
    }

    @Test
    void anUnknownOptionIsAWrongCommandLine() {
        assertEquals(2, run("--no-such-option"));
        assertTrue(err().contains("Unrecognized option: --no-such-option"), err());
        assertTrue(out().startsWith("usage: "), out());
    }

    @Test
    void aWrongValueIsAWrongCommandLine() throws IOException {
        Path primers = Files.writeString(directory.resolve("primers.fasta"), ">a-F\nACGT\n");

        assertEquals(2, run("-i", directory.toString(), "-o", directory.resolve("out").toString(),
                "-p", primers.toString(), "-t", "none"));
        assertFalse(err().isBlank());
    }

    @Test
    @Tag("end-to-end")
    void theCommandLineRunsThePipeline() throws IOException {
        // The five-isolate example, with the BLAST+ of the checkout (Git LFS) as in EndToEndTest.
        AppPaths.RuntimeLayout layout;
        try {
            layout = AppPaths.discover();
        } catch (RuntimeException e) {
            assumeTrue(false, "BLAST+ is not in runtime/: " + e.getMessage());
            return;
        }
        assumeTrue(Files.size(layout.blastnExecutable()) > 100_000, "blastn is a Git LFS pointer");
        Path example = Path.of("docs", "examples", "five-isolates").toAbsolutePath();
        Path output = directory.resolve("results");

        assertEquals(0, run("-i", example.resolve("genomes").toString(), "-o", output.toString(),
                "-p", example.resolve("primers.fasta").toString(), "-t", "2"), err());

        assertEquals(Files.readString(example.resolve("report.tsv")).replace("\r", ""),
                Files.readString(output.resolve("consolidated_report").resolve("report.tsv")).replace("\r", ""));
    }

    @Test
    void aRunThatFailsForAReasonTheUserCanFixIsItsMessage() throws IOException {
        // A primer ID without -F/-R/-P; when the tools are missing, that is the message instead.
        Files.writeString(directory.resolve("isolate.fasta"), ">contig\nACGTACGTACGTACGTACGTACGT\n");
        Path primers = Files.writeString(directory.resolve("primers.fasta"), ">noSuffix\nACGTACGTACGTACGTAC\n");

        assertEquals(1, run("-i", directory.toString(), "-o", directory.resolve("out").toString(),
                "-p", primers.toString(), "-t", "1"));
        assertTrue(err().startsWith("Error: "), err());
        assertFalse(err().contains("\tat "), "a stack trace: " + err());
    }

    @Test
    void argumentsLeftOverAreAWrongCommandLine() throws IOException {
        // -i reads/*.fastq.gz, expanded by the shell: -i gets the first file, the others are left over.
        Path primers = Files.writeString(directory.resolve("primers.fasta"), ">a-F\nACGT\n>a-R\nACGT\n");
        Path first = Files.writeString(directory.resolve("a_R1.fastq"), "@r\nACGT\n+\nIIII\n");

        assertEquals(2, run("-i", first.toString(), directory.resolve("a_R2.fastq").toString(),
                "-o", directory.resolve("out").toString(), "-p", primers.toString()));
        assertTrue(err().startsWith("Unexpected argument(s): " + directory.resolve("a_R2.fastq")), err());
    }

    @Test
    void anInputWithoutASequenceFileIsAFailedRun() throws IOException {
        // A mistyped -i must not end as a run that worked: a workflow manager reads exit code 0.
        Path input = Files.createDirectories(directory.resolve("empty"));
        Files.writeString(input.resolve("notes.txt"), "not a sequence");
        Path primers = Files.writeString(directory.resolve("primers.fasta"),
                ">a-F\nACGTACGTACGTACGTAC\n>a-R\nACGTACGTACGTACGTAC\n");

        assertEquals(1, run("-i", input.toString(), "-o", directory.resolve("out").toString(),
                "-p", primers.toString(), "-t", "1"));
        assertTrue(err().contains("No FASTA or FASTQ file in " + input + ": nothing to analyse")
                || err().contains("Missing"), err());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX,
            org.junit.jupiter.api.condition.OS.WINDOWS})
    void aCommandLineRunKilledStopsItsPrograms() throws Exception {
        // kill <pid>, a workflow manager stopping its job: BBTools went on running and writing. On Windows,
        // destroy() is Task Manager's End task (TerminateProcess): no shutdown hook runs, and blastn,
        // which prints nothing while it searches, ran on to its end.
        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        Process run = new ProcessBuilder(javaCommand, "-cp", System.getProperty("java.class.path"),
                KilledRun.class.getName()).redirectErrorStream(true).start();
        ProcessHandle program = null;
        for (int i = 0; i < 100 && program == null; i++) {
            Thread.sleep(100);
            String sleeper = KilledRun.SLEEP.getFirst();
            program = run.toHandle().descendants().filter(child -> child.info().command().orElse("")
                            .toLowerCase(java.util.Locale.ROOT).matches(".*[\\\\/]?" + sleeper + "(\\.exe)?"))
                    .findFirst().orElse(null);
        }
        assertTrue(program != null, "the program never started");

        run.destroy(); // SIGTERM; on Windows, TerminateProcess
        assertTrue(run.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        ProcessHandle started = program;
        for (int i = 0; i < 50 && started.isAlive(); i++) {
            Thread.sleep(100);
        }
        assertFalse(started.isAlive(), "the program was left running");
    }
}
