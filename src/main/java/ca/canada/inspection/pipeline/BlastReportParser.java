package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.insilicopcr.Sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Parses BLAST TSV output and attaches accepted primer hits to samples. */
final class BlastReportParser {
    void parse(PipelineContext context) {
        for (Path report : listTsvFiles(context.directories().detailed())) {
            parseSingleReport(report, context.primers(), context.config().mismatches(), context.samples());
        }
    }

    private static List<Path> listTsvFiles(Path detailedReport) {
        try (Stream<Path> paths = Files.walk(detailedReport, 2)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tsv"))
                    .toList();
        } catch (IOException e) {
            throw new PipelineException("Could not list BLAST report files in: " + detailedReport, e);
        }
    }

    private static void parseSingleReport(Path sampleReport, Map<String, String> primers,
                                          int allowedMismatches, Map<String, Sample> samples) {
        String sampleName = sampleReport.getFileName().toString().replaceFirst("\\.tsv$", "");
        Sample sample = samples.get(sampleName);
        if (sample == null) {
            throw new PipelineException("BLAST report has no matching sample entry: " + sampleName);
        }

        try (BufferedReader reader = Files.newBufferedReader(sampleReport, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("qseqid")) {
                    continue;
                }
                parseBlastLine(line, sampleName, primers, allowedMismatches).ifPresent(result ->
                        sample.addBlastResult(result.getSubjectID(), result));
            }
        } catch (IOException e) {
            throw new PipelineException("Could not parse BLAST report: " + sampleReport, e);
        }
    }

    private static Optional<BlastResult> parseBlastLine(String line, String sampleName,
                                                        Map<String, String> primers, int allowedMismatches) {
        String[] fields = line.split("\t", -1);
        if (fields.length < 15) {
            return Optional.empty();
        }

        String qseqid = fields[0];
        String sseqid = fields[1];
        String primer = primers.get(sseqid);
        if (primer == null) {
            return Optional.empty();
        }

        int length = Integer.parseInt(fields[8]);
        int mismatches = Integer.parseInt(fields[3]);
        int expectedLength = primer.length();
        if (length > expectedLength || length < expectedLength - 2 || mismatches > allowedMismatches) {
            return Optional.empty();
        }

        return Optional.of(new BlastResult(
                sampleName,
                qseqid,
                sseqid,
                mismatches,
                Integer.parseInt(fields[9]),
                Integer.parseInt(fields[10]),
                length,
                fields[14]
        ));
    }
}
