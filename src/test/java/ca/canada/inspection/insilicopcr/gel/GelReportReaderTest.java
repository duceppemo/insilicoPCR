package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GelReportReaderTest {

    @TempDir
    Path dir;

    private Path report(String... rows) throws IOException {
        StringBuilder sb = new StringBuilder("Sample\tGene\tGenomeLocation\tAmpliconSize\tContig\n");
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        return Files.writeString(dir.resolve("report.tsv"), sb);
    }

    @Test
    void readsBandsAndSortsLanesAndSizes() throws IOException {
        Path file = report(
                "sampleB\tgeneX\t1-100\t100\tc1",
                "sampleA\tgeneY\t1-500\t500\tc1",
                "sampleA\tgeneX\t1-900\t900\tc1");
        // Without named samples, lanes keep report row order...
        LinkedHashMap<String, List<GelBand>> lanes = GelReportReader.read(file, new HashMap<>());
        assertEquals(List.of("sampleB", "sampleA"), List.copyOf(lanes.keySet()));

        // ...while named samples are laid out alphabetically.
        HashMap<String, Sample> named = new HashMap<>();
        named.put("sampleB", null);
        named.put("sampleA", null);
        assertEquals(List.of("sampleA", "sampleB"), List.copyOf(GelReportReader.read(file, named).keySet()));

        // Bands within a lane are sorted by descending size.
        assertEquals(List.of(900, 500), lanes.get("sampleA").stream().map(GelBand::ampliconSize).toList());
        assertEquals("geneX", lanes.get("sampleA").getFirst().geneName());
    }

    @Test
    void emptyLanesAreKeptOnlyWhenSamplesAreNamed() throws IOException {
        Path file = report("pos\tgeneX\t1-100\t100\tc1");
        HashMap<String, Sample> named = new HashMap<>();
        named.put("pos", null);
        named.put("negativeControl", null);

        assertTrue(GelReportReader.read(file, named).containsKey("negativeControl"));
        assertTrue(GelReportReader.read(file, named).get("negativeControl").isEmpty());
        // Without named samples, empty lanes are dropped.
        assertEquals(List.of("pos"), List.copyOf(GelReportReader.read(file, new HashMap<>()).keySet()));
    }

    @Test
    void malformedRowsAreSkipped() throws IOException {
        Path file = report(
                "short\trow",
                "sampleA\tgeneX\t1-100\tnot-a-number\tc1",
                "",
                "sampleA\tgeneX\t1-100\t100\tc1");
        LinkedHashMap<String, List<GelBand>> lanes = GelReportReader.read(file, new HashMap<>());
        assertEquals(1, lanes.get("sampleA").size());
    }

    @Test
    void missingReportIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GelReportReader.read(dir.resolve("nope.tsv"), new HashMap<>()));
        assertThrows(IllegalArgumentException.class, () -> GelReportReader.read(null, new HashMap<>()));
    }

    @Test
    void roundingFollowsTheLadderResolution() {
        assertEquals(700, GelReportReader.roundAmpliconSizeForGel(702));   // >=300: nearest 25 -> 700
        assertEquals(110, GelReportReader.roundAmpliconSizeForGel(112));   // <300: nearest 10
        assertEquals(300, GelReportReader.roundAmpliconSizeForGel(299));
        assertEquals(1000, GelReportReader.roundAmpliconSizeForGel(1024)); // >=1000: nearest 50
        assertEquals(0, GelReportReader.roundAmpliconSizeForGel(4));
    }

    @Test
    void groupByRoundedSizeMergesNearIdenticalBands() {
        List<GelBand> bands = List.of(
                new GelBand("s", "geneX", 500),
                new GelBand("s", "geneY", 505),
                new GelBand("s", "geneZ", 112));
        Map<Integer, List<GelBand>> grouped = GelReportReader.groupByRoundedSize(bands);
        assertEquals(2, grouped.get(500).size());
        assertEquals(1, grouped.get(110).size());
    }
}
