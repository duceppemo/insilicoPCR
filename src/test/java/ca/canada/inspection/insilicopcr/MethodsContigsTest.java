package ca.canada.inspection.insilicopcr;

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
}
