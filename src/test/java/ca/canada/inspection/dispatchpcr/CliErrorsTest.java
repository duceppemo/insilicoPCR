package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.pipeline.PipelineException;
import ca.canada.inspection.util.ProcessRunner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliErrorsTest {

    @Test
    void aRejectedPrimerFileIsItsMessageOnce() {
        // PrimerService wraps the rejected value and repeats its message with the file added.
        IllegalArgumentException rejected = new IllegalArgumentException(
                "Assay stxQ declares size= twice with different values: 118 on stxQ-F, 300 on stxQ-R");
        PipelineException error = new PipelineException(rejected.getMessage() + " (primer file /data/p.fasta)", rejected);

        assertTrue(CliErrors.isForTheUser(error));
        assertEquals(List.of("Error: Assay stxQ declares size= twice with different values: 118 on stxQ-F,"
                + " 300 on stxQ-R (primer file /data/p.fasta)"), CliErrors.lines(error));
    }

    @Test
    void aCauseThatSaysMoreIsAddedOnItsOwnLine() {
        IOException missing = new NoSuchFileException("/data/missing.fasta");
        PipelineException error = new PipelineException("Could not parse contigs from: sample_1", missing);

        assertTrue(CliErrors.isForTheUser(error));
        assertEquals(List.of("Error: Could not parse contigs from: sample_1", "  Cause: /data/missing.fasta"),
                CliErrors.lines(error));
    }

    @Test
    void aCauseWithoutAMessageIsNamed() {
        PipelineException error = new PipelineException("Could not write QA log: /data/QAlog.txt", new IOException());

        assertEquals(List.of("Error: Could not write QA log: /data/QAlog.txt", "  Cause: IOException"),
                CliErrors.lines(error));
    }

    @Test
    void aFailedExternalToolIsItsCommandLine() {
        // blastn has printed its own message; the stage names itself and the command follows.
        ProcessRunner.Result result = new ProcessRunner.Result(1, Duration.ofSeconds(2),
                List.of("blastn", "-query", "/data/sample.fasta"));
        ProcessRunner.ProcessException failed = new ProcessRunner.ProcessException(
                "External command failed: " + result.commandLine() + " (exit 1, 2s)", result);
        PipelineException error = new PipelineException("BLAST failed", failed);

        assertTrue(CliErrors.isForTheUser(error));
        assertEquals(List.of("Error: BLAST failed",
                "  Cause: External command failed: blastn -query /data/sample.fasta (exit 1, 2s)"),
                CliErrors.lines(error));
    }

    @Test
    void aBugKeepsItsStackTrace() {
        assertFalse(CliErrors.isForTheUser(new NullPointerException("oops")));
        // A stage that failed on a programming error is not something the user can fix.
        assertFalse(CliErrors.isForTheUser(new PipelineException("BLAST failed", new IllegalStateException("oops"))));
    }
}
