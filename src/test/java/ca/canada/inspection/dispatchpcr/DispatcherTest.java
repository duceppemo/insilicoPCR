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
}
