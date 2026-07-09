package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.Sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads consolidated report TSV files into lane-ordered gel band metadata. */
public final class GelReportReader {
    private GelReportReader() {
    }

    public static LinkedHashMap<String, List<GelBand>> read(Path consolidatedReport,
                                                             HashMap<String, Sample> sampleDict) {
        if (consolidatedReport == null || !Files.isRegularFile(consolidatedReport)) {
            throw new IllegalArgumentException("Consolidated report not found: " + consolidatedReport);
        }

        LinkedHashMap<String, List<GelBand>> lanes = new LinkedHashMap<>();
        boolean keepEmptySampleLanes = sampleDict != null && !sampleDict.isEmpty();
        if (sampleDict != null) {
            sampleDict.keySet().stream()
                    .sorted()
                    .forEach(sampleName -> lanes.put(sampleName, new ArrayList<>()));
        }

        try (BufferedReader reader = Files.newBufferedReader(consolidatedReport, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("Sample\t")) {
                    continue;
                }

                String[] fields = line.split("\t", -1);
                if (fields.length < 4) {
                    continue;
                }

                int ampliconSize;
                try {
                    ampliconSize = Integer.parseInt(fields[3]);
                } catch (NumberFormatException ignored) {
                    continue;
                }

                String sampleName = fields[0];
                String geneName = fields[1];
                lanes.computeIfAbsent(sampleName, key -> new ArrayList<>())
                        .add(new GelBand(sampleName, geneName, ampliconSize));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read consolidated report: " + consolidatedReport, e);
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
