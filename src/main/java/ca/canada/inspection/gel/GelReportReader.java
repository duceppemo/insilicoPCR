package ca.canada.inspection.gel;

import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.report.ReportColumn;
import ca.canada.inspection.report.ReportTsv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads consolidated report TSV files ({@link ReportTsv}) into lane-ordered gel band metadata. */
public final class GelReportReader {
    private GelReportReader() {
    }

    /**
     * @param sampleNames the run's samples, each a lane even without a band; {@code null} or
     *                    empty for lanes of the samples with a band only
     */
    public static LinkedHashMap<String, List<GelBand>> read(Path consolidatedReport,
                                                             Collection<String> sampleNames) {
        if (consolidatedReport == null || !Files.isRegularFile(consolidatedReport)) {
            throw new IllegalArgumentException("Consolidated report not found: " + consolidatedReport);
        }

        // Named as report.tsv names them: a tab or line break in a name is a space there.
        List<String> named = sampleNames == null ? null : sampleNames.stream().map(ReportColumn::readBack).toList();
        return lanes(ReportTsv.readChecked(consolidatedReport).stream()
                .map(checked -> GelBand.of(checked.row(), checked.sizeCheck())).toList(), named);
    }

    /**
     * The lanes of a run's rows as {@link #read} gives those of its report, for the gel {@code --gel} draws: the
     * run has them, and its report need not be read back.
     *
     * @param sizes the product sizes the primer file declares, for each band's size check
     */
    public static LinkedHashMap<String, List<GelBand>> lanes(List<ReportRow> rows, ExpectedSizes sizes,
                                                              Collection<String> sampleNames) {
        return lanes(rows.stream().map(row -> GelBand.of(row, sizes.label(row.assay(), row.size()))).toList(),
                sampleNames);
    }

    private static LinkedHashMap<String, List<GelBand>> lanes(List<GelBand> all, Collection<String> sampleNames) {
        LinkedHashMap<String, List<GelBand>> lanes = new LinkedHashMap<>();
        boolean keepEmptySampleLanes = sampleNames != null && !sampleNames.isEmpty();
        if (sampleNames != null) {
            sampleNames.stream().sorted().forEach(sampleName -> lanes.put(sampleName, new ArrayList<>()));
        }
        for (GelBand band : all) {
            lanes.computeIfAbsent(band.sampleName(), key -> new ArrayList<>()).add(band);
        }

        if (!keepEmptySampleLanes) {
            lanes.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
        lanes.replaceAll((sampleName, bands) -> bands.stream()
                .sorted(Comparator.comparingInt(GelBand::ampliconSize).reversed()
                        .thenComparing(GelBand::geneName))
                .toList());
        return lanes;
    }

    public static Map<Integer, List<GelBand>> groupByRoundedSize(List<GelBand> bands) {
        Map<Integer, List<GelBand>> grouped = new LinkedHashMap<>();
        for (GelBand band : bands) {
            grouped.computeIfAbsent(roundAmpliconSizeForGel(band.ampliconSize()), key -> new ArrayList<>())
                    .add(band);
        }
        return grouped;
    }

    public static int roundAmpliconSizeForGel(int size) {
        if (size >= 1000) {
            return Math.round(size / 50.0f) * 50;
        }
        if (size >= 300) {
            return Math.round(size / 25.0f) * 25;
        }
        return Math.max(0, Math.round(size / 10.0f) * 10);
    }
}
