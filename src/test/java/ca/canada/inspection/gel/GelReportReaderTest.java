package ca.canada.inspection.gel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void theLanesOfARunsRowsAreThoseOfItsReportReadBack() throws IOException {
        // What --gel draws, from the rows in memory: the same lanes, bands and size checks as from report.tsv.
        Map<String, String> sequences = new java.util.LinkedHashMap<>();
        for (String id : List.of("toxA-F", "toxA-R")) {
            sequences.put(id, "ACGTACGTACGTACGTACGT");
        }
        ca.canada.inspection.pcr.ExpectedSizes sizes =
                ca.canada.inspection.pcr.ExpectedSizes.fromHeaders(Map.of("toxA-F", "size=312"));
        ca.canada.inspection.pcr.PrimerPanel primers = ca.canada.inspection.pcr.PrimerPanel.of(sequences, sizes,
                ca.canada.inspection.pcr.MixGroups.fromHeaders(Map.of()));
        List<ca.canada.inspection.pcr.ReportRow> rows = List.of(row("s2", 312), row("s1", 312), row("s1", 900));
        Path report = ca.canada.inspection.report.ConsolidatedReport.write(dir, rows, primers);
        List<String> samples = List.of("s1", "s2", "negative");

        assertEquals(shown(GelReportReader.read(report, samples)), shown(GelReportReader.lanes(rows, sizes, samples)));
        assertEquals(List.of("negative", "s1", "s2"), List.copyOf(GelReportReader.lanes(rows, sizes, samples).keySet()));
    }

    private static ca.canada.inspection.pcr.ReportRow row(String sample, int size) {
        return new ca.canada.inspection.pcr.ReportRow(sample, "toxA",
                new ca.canada.inspection.pcr.ReportRow.Site(1, size, size, "c1", "", '+'),
                new ca.canada.inspection.pcr.ReportRow.Binding("toxA-F", 0, 0, 0, 60.0),
                new ca.canada.inspection.pcr.ReportRow.Binding("toxA-R", 1, 0, 0, 57.5), null,
                ca.canada.inspection.pcr.ReportRow.Source.ASSEMBLY);
    }

    /** Each lane's bands as the gel window's hover text gives them. */
    private static Map<String, List<String>> shown(LinkedHashMap<String, List<GelBand>> lanes) {
        Map<String, List<String>> shown = new LinkedHashMap<>();
        lanes.forEach((sample, bands) -> shown.put(sample,
                bands.stream().map(band -> GelText.tooltip(List.of(band))).toList()));
        return shown;
    }

    @Test
    void readsBandsAndSortsLanesAndSizes() throws IOException {
        Path file = report(
                "sampleB\tgeneX\t1-100\t100\tc1",
                "sampleA\tgeneY\t1-500\t500\tc1",
                "sampleA\tgeneX\t1-900\t900\tc1");
        // Without named samples, lanes keep report row order...
        LinkedHashMap<String, List<GelBand>> lanes = GelReportReader.read(file, List.of());
        assertEquals(List.of("sampleB", "sampleA"), List.copyOf(lanes.keySet()));

        // ...while named samples are laid out alphabetically.
        List<String> named = List.of("sampleB", "sampleA");
        assertEquals(List.of("sampleA", "sampleB"), List.copyOf(GelReportReader.read(file, named).keySet()));

        // Bands within a lane are sorted by descending size.
        assertEquals(List.of(900, 500), lanes.get("sampleA").stream().map(GelBand::ampliconSize).toList());
        assertEquals("geneX", lanes.get("sampleA").getFirst().geneName());
    }

    @Test
    void emptyLanesAreKeptOnlyWhenSamplesAreNamed() throws IOException {
        Path file = report("pos\tgeneX\t1-100\t100\tc1");
        List<String> named = List.of("pos", "negativeControl");

        assertTrue(GelReportReader.read(file, named).containsKey("negativeControl"));
        assertTrue(GelReportReader.read(file, named).get("negativeControl").isEmpty());
        // Without named samples, empty lanes are dropped.
        assertEquals(List.of("pos"), List.copyOf(GelReportReader.read(file, List.of()).keySet()));
    }

    @Test
    void malformedRowsAreSkipped() throws IOException {
        Path file = report(
                "short\trow",
                "sampleA\tgeneX\t1-100\tnot-a-number\tc1",
                "",
                "sampleA\tgeneX\t1-100\t100\tc1");
        LinkedHashMap<String, List<GelBand>> lanes = GelReportReader.read(file, List.of());
        assertEquals(1, lanes.get("sampleA").size());
    }

    @Test
    void missingReportIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GelReportReader.read(dir.resolve("nope.tsv"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> GelReportReader.read(null, List.of()));
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

    @Test
    void eachBandKeepsTheSizeCheckOfItsRow() throws IOException {
        Path file = Files.writeString(dir.resolve("checked.tsv"),
                "Sample\tGene\tGenomeLocation\tAmpliconSize\tContig\tSizeCheck\n"
                        + "s1\tits\t1-600\t600\tc1\tunexpected\n");
        assertEquals(ca.canada.inspection.pcr.ExpectedSizes.Label.UNEXPECTED,
                GelReportReader.read(file, List.of()).get("s1").getFirst().sizeCheck());
    }

    @Test
    void aBandSaysWhenItIsOfOnePrimerOrOfTwoAssays() throws IOException {
        // The report tags such products; the gel showed them as their assay's own.
        Path file = Files.writeString(dir.resolve("paired.tsv"),
                "Sample\tGene\tGenomeLocation\tAmpliconSize\tContig\tForwardPrimers\tReversePrimers\n"
                        + "s1\tone\t1-400\t400\tc1\tone-F\tone-F\n"
                        + "s1\ta+b\t1-300\t300\tc1\ta-F\tb-R\n"
                        + "s1\ta\t1-200\t200\tc1\ta-F\ta-R\n");

        List<GelBand> bands = GelReportReader.read(file, List.of()).get("s1");

        assertEquals(List.of("one primer", "two assays", ""), bands.stream().map(GelBand::pairingTag).toList());
        assertEquals("Sample: s1\nAssay: one\nAmplicon size: 400 bp\nMade by: one primer"
                        + "\nPlus-strand primer one-F: no mismatch\nMinus-strand primer one-F: no mismatch"
                        + "\nCalled on: assembly",
                GelText.tooltip(List.of(bands.getFirst())));
        assertEquals("tip\nOn: c1, 1-300\n\nRows:\ns1\ta+b\t300 bp\ttwo assays",
                GelText.details("tip", List.of(bands.get(1))));
    }

    @Test
    void eachBandKeepsItsRowForThePopup() throws IOException {
        // How each oligo binds was in the report, not on the gel: the band keeps the row it was read from.
        Path file = Files.writeString(dir.resolve("bound.tsv"), "Sample\tGene\tGenomeLocation\tAmpliconSize\tContig"
                + "\tForwardPrimer\tReversePrimer\tForwardMismatches\tReverseMismatches\tForward5PrimeUnpaired"
                + "\tReverse5PrimeUnpaired\tForwardTm\tReverseTm\n"
                + "s1\tg\t961-1119\t159\tcontig_3\tg-F\tg-R\t0\t0\t0\t1\t58.9\t51.2\n");

        GelBand band = GelReportReader.read(file, List.of()).get("s1").getFirst();

        assertEquals("g-R", band.row().reversePrimer());
        assertEquals(1, band.row().reverseUnpaired());
        assertEquals(51.2, band.row().reverseTm());
    }
}
