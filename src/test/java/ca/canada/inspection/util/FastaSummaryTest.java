package ca.canada.inspection.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FastaSummaryTest {

    private static final String FASTA = ">contig_1 chromosome\nACGTACGT\n>contig_2\nTTGGCCAA\n> contig_3 \tplasmid  \nAC\n";

    @TempDir
    Path directory;

    @Test
    void aPlainAndAGzippedAssemblyGiveTheirDescriptionsAndLengths() throws IOException {
        Path plain = Files.writeString(directory.resolve("plain.fasta"), FASTA);
        Path zipped = directory.resolve("zipped.fna.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(zipped))) {
            out.write(FASTA.getBytes(StandardCharsets.UTF_8));
        }

        for (Path fasta : new Path[]{plain, zipped}) {
            FastaSummary summary = FastaSummary.of(fasta, 31);
            assertEquals(Map.of("contig_1", "chromosome", "contig_2", "", "contig_3", "plasmid"), summary.descriptions());
            assertEquals(Map.of("contig_1", 8, "contig_2", 8, "contig_3", 2), summary.lengths());
            assertEquals(Map.of(), summary.circles());
        }
    }

    @Test
    void theCirclesAreThoseCirclesFinds() throws IOException {
        // A plasmid marked circular, beside a linear chromosome: as the circle check alone gives them.
        Path fasta = Files.writeString(directory.resolve("plasmid.fasta"),
                ">chromosome\n" + "ACGT".repeat(50) + "\n>p1 circular=true\n" + "GGCATTCA".repeat(20) + "\n");

        FastaSummary summary = FastaSummary.of(fasta, 31);

        assertEquals(Map.of("p1", 160), summary.circles());
        assertEquals(Circles.of(fasta, 31), summary.circles());
        assertEquals(Map.of("chromosome", 200, "p1", 160), summary.lengths());
    }

    @Test
    void aFileThatCannotBeReadIsAnError() {
        assertThrows(IOException.class, () -> FastaSummary.of(directory.resolve("gone.fasta"), 31));
    }
}
