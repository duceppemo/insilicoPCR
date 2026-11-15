package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputValidatorTest {

    @TempDir
    Path dir;

    private Path fasta(String name) throws IOException {
        return Files.writeString(dir.resolve(name), ">seq1\nACGTACGT\n");
    }

    private Path primers() throws IOException {
        return Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGT\n>a-R\nACGT\n");
    }

    @Test
    void validSingleFileInputPasses() throws IOException {
        Path input = fasta("genome.fasta");
        Path primers = Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGT\n>a-R\nACGT\n");
        assertEquals("", InputValidator.validate(input, dir.resolve("out"), primers));
    }

    @Test
    void validDirectoryInputPasses() throws IOException {
        Path inputDir = Files.createDirectories(dir.resolve("inputs"));
        Files.writeString(inputDir.resolve("genome.fasta"), ">seq1\nACGT\n");
        Path primers = Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGT\n>a-R\nACGT\n");
        assertEquals("", InputValidator.validate(inputDir, dir.resolve("out"), primers));
    }

    @Test
    void nullsAreReported() throws IOException {
        Path primers = primers();
        assertTrue(InputValidator.validate(null, dir, primers).contains("Please enter"));
        assertTrue(InputValidator.validate(primers, dir, null).contains("Please enter"));
    }

    @Test
    void nonFastaInputIsReported() throws IOException {
        Path notFasta = Files.writeString(dir.resolve("notes.fasta"), "this is not fasta\n");
        Path primers = primers();
        assertEquals("The input must be a FASTA or FASTQ file, or a folder of them",
                InputValidator.validate(notFasta, dir, primers));
        assertTrue(InputValidator.validate(primers, dir, notFasta).contains("has a sequence before its first header"));
    }

    @Test
    void thePrimerFileIsWhatTheRunReadsWhateverItsName() throws IOException {
        // As on the command line: primers.txt is read; a gzipped file is read too.
        Path input = Files.writeString(dir.resolve("genome.fasta"), ">c1\nACGT\n");
        String panel = ">a-F\nACGTACGTACGT\n>a-R\nTTTTGGGGCCCC\n";
        Path text = Files.writeString(dir.resolve("primers.txt"), panel);
        Path gzipped = dir.resolve("primers.fasta.gz");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gzipped))) {
            out.write(panel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals("", InputValidator.validate(input, dir.resolve("out"), text));
        assertEquals("", InputValidator.validate(input, dir.resolve("out"), gzipped));
        assertEquals(2, PrimerService.read(gzipped).sequences().size());
        assertTrue(InputValidator.validate(input, dir.resolve("out"), dir.resolve("none.fasta"))
                .startsWith("Primer file does not exist"));
    }

    @Test
    void probeWithoutPrimersIsReported() throws IOException {
        Path input = fasta("genome.fasta");
        Path primers = Files.writeString(dir.resolve("probe_only.fasta"), ">assay-P\nACGTACGT\n");
        assertTrue(InputValidator.validate(input, dir, primers).contains("assay-F, assay-R are missing"),
                InputValidator.validate(input, dir, primers));
    }

    @Test
    void emptyInputDirectoryIsReported() throws IOException {
        Path inputDir = Files.createDirectories(dir.resolve("empty"));
        Path primers = primers();
        assertTrue(InputValidator.validate(inputDir, dir, primers).contains("at least one"));
    }

    @Test
    void aFaultyFileAmongGoodOnesDoesNotStopTheRun() throws IOException {
        // The run names it, leaves its sample out and analyses the others (InputCheck).
        Path inputDir = Files.createDirectories(dir.resolve("mixed"));
        Files.writeString(inputDir.resolve("good.fasta"), ">seq1\nACGT\n");
        Files.writeString(inputDir.resolve("bad.fasta"), "no header here\n");
        Files.writeString(inputDir.resolve("empty_R1.fastq"), "");
        Path primers = primers();
        assertEquals("", InputValidator.validate(inputDir, dir, primers));
    }

    @Test
    void aFolderOfFaultyFilesOnlyIsReported() throws IOException {
        Path inputDir = Files.createDirectories(dir.resolve("allbad"));
        Files.writeString(inputDir.resolve("empty_R1.fastq"), "");
        Path primers = primers();
        assertTrue(InputValidator.validate(inputDir, dir, primers).contains("at least one"));
    }

    @Test
    void primerHeadersWithDescriptionsAndSizeRangesPass() throws IOException {
        // "size=420-450" holds a dash: the role must still be read from the ID alone.
        Path input = fasta("genome2.fasta");
        Path primers = Files.writeString(dir.resolve("sized.fasta"),
                ">a-F size=420-450 other=575\nACGT\n>a-R reverse primer\nACGT\n>a-P\tFAM-BHQ1\nACGT\n");
        assertEquals("", InputValidator.validate(input, dir.resolve("out2"), primers));
    }
}
