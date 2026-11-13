package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.util.SequenceFileUtils;
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
        Map<String, Sample> samples = new LinkedHashMap<>(SequenceFileUtils.createSampleMap(directory));

        new ContigService().load(contextOf(samples));

        Map<String, String> expected = Map.of("contig_1", "chromosome", "contig_2", "", "contig_3", "plasmid");
        assertEquals(expected, samples.get("plain").contigs());
        assertEquals(expected, samples.get("zipped").contigs());
    }

    @Test
    void theContigsOfAFastqSampleAreThoseOfItsAssembly() throws IOException {
        Files.writeString(directory.resolve("reads.fastq"), "@r1\nACGT\n+\nIIII\n");
        Map<String, Sample> samples = new LinkedHashMap<>(SequenceFileUtils.createSampleMap(directory));
        Sample reads = samples.get("reads");
        reads.setAssemblyFile(Files.writeString(directory.resolve("reads_assembly.fasta"), ">contig_9 cov=3\nACGT\n"));

        new ContigService().load(contextOf(samples));

        assertEquals(Map.of("contig_9", "cov=3"), reads.contigs());
    }

    @Test
    void aFastqSampleWithoutAnAssemblyIsAFailureForTheUser() throws IOException {
        Files.writeString(directory.resolve("reads.fastq"), "@r1\nACGT\n+\nIIII\n");
        Map<String, Sample> samples = new LinkedHashMap<>(SequenceFileUtils.createSampleMap(directory));

        PipelineException error = assertThrows(PipelineException.class,
                () -> new ContigService().load(contextOf(samples)));

        assertEquals("Sample has no FASTA/assembly file for contig loading: reads", error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void anAssemblyThatCannotBeReadIsAFailureForTheUser() throws IOException {
        Path fasta = Files.writeString(directory.resolve("gone.fasta"), FASTA);
        Map<String, Sample> samples = new LinkedHashMap<>(SequenceFileUtils.createSampleMap(directory));
        Files.delete(fasta);

        PipelineException error = assertThrows(PipelineException.class,
                () -> new ContigService().load(contextOf(samples)));

        assertEquals("Could not parse contigs from: " + fasta, error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    private static PipelineContext contextOf(Map<String, Sample> samples) {
        return new PipelineContext(null, null, null, samples, Map.of(), null, 1, List.of(), RunMonitor.CONSOLE);
    }
}
