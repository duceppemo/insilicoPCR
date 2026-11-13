package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.pipeline.PipelineException;
import ca.canada.inspection.pipeline.RunFailure;
import ca.canada.inspection.util.SequenceFileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodsContigsTest {

    private static final String FASTA = ">contig_1 chromosome\nACGTACGT\n>contig_2\nTTGGCCAA\n";

    @TempDir
    Path directory;

    @Test
    void contigsOfAPlainAndOfAGzippedAssemblyAreRead() throws IOException {
        Files.writeString(directory.resolve("plain.fasta"), FASTA);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(directory.resolve("zipped.fna.gz")))) {
            out.write(FASTA.getBytes(StandardCharsets.UTF_8));
        }
        HashMap<String, Sample> samples = new HashMap<>(SequenceFileUtils.createSampleMap(directory));

        Methods.addContigDict(samples);

        Map<String, String> expected = Map.of("contig_1", "chromosome", "contig_2", "");
        assertEquals(expected, samples.get("plain").contigs());
        assertEquals(expected, samples.get("zipped").contigs());
    }

    @Test
    void anAssemblyThatCannotBeReadIsAFailureForTheUser() throws IOException {
        Path fasta = Files.writeString(directory.resolve("gone.fasta"), FASTA);
        HashMap<String, Sample> samples = new HashMap<>(SequenceFileUtils.createSampleMap(directory));
        Files.delete(fasta);

        PipelineException error = assertThrows(PipelineException.class, () -> Methods.addContigDict(samples));

        assertTrue(error.getMessage().startsWith("Unable to read sequence file for sample: gone"), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void aPrimerFileThatCannotBeReadIsAFailureForTheUser() {
        Path missing = directory.resolve("missing.fasta");

        PipelineException error = assertThrows(PipelineException.class, () -> Methods.parseFastaToDictionary(missing));

        assertEquals("Unable to parse FASTA file: " + missing, error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void makeblastdbThatCannotBeStartedIsAFailureForTheUser() throws IOException {
        Path primers = Files.writeString(directory.resolve("primers.fasta"), ">a-F\nACGTACGTACGTACGTAC\n");
        Path noBlastHere = Files.createDirectory(directory.resolve("empty"));

        PipelineException error = assertThrows(PipelineException.class,
                () -> Methods.makeBlastDB(primers, noBlastHere, null));

        assertEquals("Unable to run makeblastdb for reference: " + primers, error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }
}
