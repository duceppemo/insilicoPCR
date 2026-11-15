package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.util.InputCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContigServiceTest {

    private static final String FASTA = ">contig_1 chromosome\nACGTACGT\n>contig_2\nTTGGCCAA\n> contig_3 \tplasmid  \nAC\n";

    @TempDir
    Path directory;

    @Test
    void contigsOfAPlainAndOfAGzippedAssemblyAreRead() throws IOException {
        Files.writeString(directory.resolve("plain.fasta"), FASTA);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(directory.resolve("zipped.fna.gz")))) {
            out.write(FASTA.getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Sample> samples = new LinkedHashMap<>(InputCheck.scan(directory, InputCheck.Depth.QUICK, 1).samples());

        ContigService.load(contextOf(samples));

        Map<String, String> expected = Map.of("contig_1", "chromosome", "contig_2", "", "contig_3", "plasmid");
        assertEquals(expected, samples.get("plain").hits().descriptions());
        assertEquals(expected, samples.get("zipped").hits().descriptions());
    }

    @Test
    void theContigsOfAFastqSampleAreThoseOfItsAssembly() throws IOException {
        Files.writeString(directory.resolve("reads.fastq"), "@r1\nACGT\n+\nIIII\n");
        Map<String, Sample> samples = new LinkedHashMap<>(InputCheck.scan(directory, InputCheck.Depth.QUICK, 1).samples());
        Sample reads = samples.get("reads");
        PipelineContext context = contextOf(samples);
        Path assembly = context.directories().sample("reads").assembly();
        Files.createDirectories(assembly.getParent());
        Files.writeString(assembly, ">contig_9 cov=3\nACGT\n");

        ContigService.load(context);

        assertEquals(Map.of("contig_9", "cov=3"), reads.hits().descriptions());
    }

    @Test
    void aFastqSampleWithoutAnAssemblyIsAFailureForTheUser() throws IOException {
        Files.writeString(directory.resolve("reads.fastq"), "@r1\nACGT\n+\nIIII\n");
        Map<String, Sample> samples = new LinkedHashMap<>(InputCheck.scan(directory, InputCheck.Depth.QUICK, 1).samples());

        PipelineException error = assertThrows(PipelineException.class,
                () -> ContigService.load(contextOf(samples)));

        assertEquals("FASTQ sample reads has no mini-assembly: "
                + contextOf(samples).directories().sample("reads").assembly(), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void anAssemblyThatCannotBeReadIsAFailureForTheUser() throws IOException {
        Path fasta = Files.writeString(directory.resolve("gone.fasta"), FASTA);
        Map<String, Sample> samples = new LinkedHashMap<>(InputCheck.scan(directory, InputCheck.Depth.QUICK, 1).samples());
        Files.delete(fasta);

        PipelineException error = assertThrows(PipelineException.class,
                () -> ContigService.load(contextOf(samples)));

        assertEquals("Could not parse contigs from: " + fasta, error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    private PipelineContext contextOf(Map<String, Sample> samples) {
        Path output = directory.resolve("out");
        return new PipelineContext(null, null, new PipelineDirectories(output, output.resolve("detailed_report"),
                output.resolve(PipelineDirectories.CONSOLIDATED)), samples, PrimerPanel.of(Map.of()), 1, List.of(),
                RunMonitor.CONSOLE);
    }
}
