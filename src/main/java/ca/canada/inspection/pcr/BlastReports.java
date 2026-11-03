package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Walks per-sample BLAST TSV reports and attaches accepted primer hits to the
 * matching samples. Shared by the command-line and GUI pipelines.
 */
public final class BlastReports {

    private BlastReports() {
    }

    public static void attachHits(Path detailedDir, Map<String, Sample> samples,
                                  Map<String, String> primers, AmpliconOptions options) {
        for (Path report : listTsvFiles(detailedDir)) {
            String sampleName = report.getFileName().toString().replaceFirst("\\.tsv$", "");
            Sample sample = samples.get(sampleName);
            if (sample == null) {
                continue;
            }
            parseSingleReport(report, sampleName, sample, primers, options);
        }
    }

    private static List<Path> listTsvFiles(Path detailedDir) {
        try (Stream<Path> paths = Files.walk(detailedDir, 2)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tsv"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list BLAST reports in: " + detailedDir, e);
        }
    }

    private static void parseSingleReport(Path report, String sampleName, Sample sample,
                                          Map<String, String> primers, AmpliconOptions options) {
        try (BufferedReader reader = Files.newBufferedReader(report, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("qseqid")) {
                    continue;
                }
                BlastHitParser.parse(line, sampleName, primers, options)
                        .ifPresent(hit -> sample.addBlastResult(hit.subjectID(), hit));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse BLAST report: " + report, e);
        }
    }
}
