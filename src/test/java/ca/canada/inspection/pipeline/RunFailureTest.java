package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunFailureTest {

    @Test
    void aRejectedPrimerFileIsItsMessageOnce() {
        // PrimerService wraps the rejected value and repeats its message with the file added.
        IllegalArgumentException rejected = new IllegalArgumentException(
                "Assay stxQ declares size= twice with different values: 118 on stxQ-F, 300 on stxQ-R");
        PipelineException error = new PipelineException(rejected.getMessage() + " (primer file /data/p.fasta)");

        assertTrue(RunFailure.isForTheUser(error));
        assertEquals(List.of("Error: Assay stxQ declares size= twice with different values: 118 on stxQ-F,"
                + " 300 on stxQ-R (primer file /data/p.fasta)"), RunFailure.lines(error));
    }

    @Test
    void aCauseThatSaysMoreIsAddedOnItsOwnLine() {
        IOException missing = new NoSuchFileException("/data/missing.fasta");
        PipelineException error = new PipelineException("Could not parse contigs from: sample_1", missing);

        assertTrue(RunFailure.isForTheUser(error));
        assertEquals(List.of("Error: Could not parse contigs from: sample_1",
                        "  Cause: /data/missing.fasta: no such file or folder"),
                RunFailure.lines(error));
    }

    @Test
    void aCauseWithoutAMessageIsNamed() {
        PipelineException error = new PipelineException("Could not write QA log: /data/QAlog.txt", new IOException());

        assertEquals(List.of("Error: Could not write QA log: /data/QAlog.txt", "  Cause: IOException"),
                RunFailure.lines(error));
    }

    @Test
    void aFailedExternalToolIsItsCommandLine() {
        // blastn has printed its own message; the stage names itself and the command follows.
        PipelineException failed = new PipelineException(
                "blastn failed with exit code 1: blastn -query /data/sample.fasta");
        PipelineException error = new PipelineException("BLAST failed", failed);

        assertTrue(RunFailure.isForTheUser(error));
        assertEquals(List.of("Error: BLAST failed",
                "  Cause: blastn failed with exit code 1: blastn -query /data/sample.fasta"),
                RunFailure.lines(error));
    }

    @Test
    void aBugKeepsItsStackTrace() {
        assertFalse(RunFailure.isForTheUser(new NullPointerException("oops")));
        // A stage that failed on a programming error is not something the user can fix.
        assertFalse(RunFailure.isForTheUser(new PipelineException("BLAST failed", new IllegalStateException("oops"))));
        // A value the user gave is checked before the run: one rejected during the run is a bug.
        assertFalse(RunFailure.isForTheUser(new NumberFormatException("For input string: \"x\"")));
        assertFalse(RunFailure.isForTheUser(new PipelineException("BLAST failed",
                new IllegalArgumentException("threads must be >= 1"))));
    }

    @Test
    void aFileTheSystemRefusesIsGivenWithTheReason() {
        // An AccessDeniedException's message is only the path: the reason is in its kind.
        PipelineException error = new PipelineException("Unable to create directory: /data/out",
                new java.nio.file.AccessDeniedException("/data/out"));
        assertEquals(List.of("Error: Unable to create directory: /data/out", "  Cause: /data/out: permission denied"),
                RunFailure.lines(error));
    }

    @Test
    void aNameTheLocaleCannotEncodeIsTheUsersToFix() {
        assertTrue(RunFailure.isForTheUser(new java.nio.file.InvalidPathException("s\u00e9_R1.fastq.gz",
                "Malformed input or input contains unmappable characters")));
    }
}
