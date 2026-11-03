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
        Path primers = fasta("primers.fasta");
        assertTrue(InputValidator.validate(null, dir, primers).contains("Please enter"));
        assertTrue(InputValidator.validate(primers, dir, null).contains("Please enter"));
    }

    @Test
    void nonFastaInputIsReported() throws IOException {
        Path notFasta = Files.writeString(dir.resolve("notes.fasta"), "this is not fasta\n");
        Path primers = fasta("primers.fasta");
        assertTrue(InputValidator.validate(notFasta, dir, primers).contains("fasta format"));
        assertTrue(InputValidator.validate(primers, dir, notFasta).contains("fasta format"));
    }

    @Test
    void probeWithoutPrimersIsReported() throws IOException {
        Path input = fasta("genome.fasta");
        Path primers = Files.writeString(dir.resolve("probe_only.fasta"), ">assay-P\nACGTACGT\n");
        assertTrue(InputValidator.validate(input, dir, primers).contains("probe"));
    }

    @Test
    void emptyInputDirectoryIsReported() throws IOException {
        Path inputDir = Files.createDirectories(dir.resolve("empty"));
        Path primers = fasta("primers.fasta");
        assertTrue(InputValidator.validate(inputDir, dir, primers).contains("at least one"));
    }

    @Test
    void directoryWithMalformedSequenceFileIsReported() throws IOException {
        Path inputDir = Files.createDirectories(dir.resolve("mixed"));
        Files.writeString(inputDir.resolve("good.fasta"), ">seq1\nACGT\n");
        Files.writeString(inputDir.resolve("bad.fasta"), "no header here\n");
        Path primers = fasta("primers.fasta");
        assertTrue(InputValidator.validate(inputDir, dir, primers).contains("non-fastq/fasta"));
    }
}
