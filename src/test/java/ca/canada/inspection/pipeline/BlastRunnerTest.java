package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.util.InputCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** blastn is stood in for by a shell script, which fails as each query's name says. */
@DisabledOnOs(OS.WINDOWS)
class BlastRunnerTest {

    /** Exits 137 (killed) for a query named killed, 1 (BLAST query error) for one named bad, else writes nothing. */
    private static final String BLASTN = """
            #!/bin/sh
            query=""; out=""
            while [ $# -gt 0 ]; do case "$1" in -query) query="$2"; shift;; -out) out="$2"; shift;; esac; shift; done
            case "$query" in *killed*) exit 137;; *bad*) echo "BLAST query error: CFastaReader" >&2; exit 1;; esac
            : > "$out"
            """;

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();

    private PipelineContext context(String... names) throws IOException {
        Path output = Files.createDirectories(dir.resolve("out"));
        Path blastn = Files.createDirectories(output.resolve("blast")).resolve("blastn");
        Files.writeString(blastn, BLASTN);
        Files.setPosixFilePermissions(blastn, PosixFilePermissions.fromString("rwxr-xr-x"));
        Map<String, Sample> samples = new LinkedHashMap<>();
        for (String name : names) {
            Sample sample = new Sample(name);
            sample.setFileType(Sample.FileType.FASTA);
            sample.addFile(Files.writeString(dir.resolve(name + ".fasta"), ">c1\nACGT\n"));
            samples.put(name, sample);
        }
        PipelineContext base = TestContexts.writingTo(output, samples);
        return new PipelineContext(base.config(), base.dependencies(), base.directories(), samples, base.primers(),
                1, new ArrayList<>(), new RunMonitor() {
                    @Override
                    public void warning(String message) {
                        warnings.add(message);
                    }
                });
    }

    @Test
    void anAssemblyBlastnCannotReadLeavesItsSampleOut() throws IOException {
        PipelineContext context = context("good", "bad");

        BlastRunner.run(context);

        assertEquals(List.of("good"), List.copyOf(context.samples().keySet()));
        assertEquals(List.of("bad"), context.inputIssues().stream().map(InputCheck.Issue::sample).toList());
        assertTrue(warnings.getFirst().startsWith("Warning: bad.fasta: blastn cannot read the file"), warnings.toString());
    }

    @Test
    void aBlastnKilledFailsTheRunRatherThanCallingAGoodFileFaulty() throws IOException {
        // The out-of-memory killer took one of several blastn running at once: the file is not at fault.
        PipelineContext context = context("good", "killed");

        PipelineException error = assertThrows(PipelineException.class, () -> BlastRunner.run(context));

        // The stage reports the program's failure, as its cause.
        Throwable failed = error instanceof ProgramFailedException ? error : error.getCause();
        assertEquals(137, ((ProgramFailedException) failed).exitCode());
        assertEquals(List.of(), context.inputIssues());
    }

    @Test
    void blastnRefusingEveryAssemblyFailsTheRun() throws IOException {
        PipelineContext context = context("bad");

        PipelineException error = assertThrows(PipelineException.class, () -> BlastRunner.run(context));

        assertEquals("BLAST failed", error.getMessage());
    }
}
