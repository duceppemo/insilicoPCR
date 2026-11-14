package ca.canada.inspection.pipeline;

import ca.canada.inspection.report.ConsolidatedReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * A finished run, reopened from its output folder: the report it wrote and the samples it
 * analysed, as its {@code QAlog.txt} records them.
 *
 * <p>Since 0.15.1 the log names both ({@link QaLogWriter#REPORT_PREFIX},
 * {@link QaLogWriter#SAMPLE_PREFIX}). An older log names neither: the report is then the
 * newest one in the folder, and the samples are worked out from the logged input list
 * ({@link QaLogInputs}).</p>
 *
 * @param report  the run's consolidated report
 * @param samples the samples it analysed: one gel lane each, with or without a band
 */
public record PreviousRun(Path report, List<String> samples) {
    public PreviousRun {
        samples = List.copyOf(samples);
    }

    public static PreviousRun open(Path runDirectory) {
        Path qaLog = runDirectory.resolve("QAlog.txt");
        if (!Files.isRegularFile(qaLog)) {
            throw new PipelineException("QAlog.txt not found in " + runDirectory
                    + ": not the output folder of a finished run");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(qaLog, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PipelineException("Unable to read " + qaLog, e);
        }
        Optional<String> reportLine = valueOf(lines, QaLogWriter.REPORT_PREFIX).stream().findFirst();
        if (reportLine.isEmpty()) {
            return new PreviousRun(latestReport(runDirectory), legacySamples(qaLog));
        }
        Path report = runDirectory.resolve(reportLine.get());
        if (!Files.isRegularFile(report)) {
            throw new PipelineException("The report of the run in " + runDirectory + " is missing: " + report);
        }
        return new PreviousRun(report, valueOf(lines, QaLogWriter.SAMPLE_PREFIX));
    }

    private static List<String> valueOf(List<String> lines, String prefix) {
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                values.add(line.substring(prefix.length()).strip());
            }
        }
        return values;
    }

    /** The samples of a log written before 0.15.1, from its input list. */
    private static List<String> legacySamples(Path qaLog) {
        Set<String> samples = new TreeSet<>(QaLogInputs.sampleNames(QaLogInputs.read(qaLog)));
        // A sample left out for a faulty file is not a lane without a band.
        samples.removeAll(QaLogInputs.samplesNotAnalysed(qaLog));
        if (samples.isEmpty()) {
            throw new PipelineException("No input samples were found in " + qaLog);
        }
        return List.copyOf(samples);
    }

    /** The newest consolidated report of a run folder written before 0.15.1. */
    static Path latestReport(Path runDirectory) {
        Path reportDir = runDirectory.resolve(PipelineDirectories.CONSOLIDATED);
        if (!Files.isDirectory(reportDir)) {
            throw new PipelineException("Consolidated report folder not found: " + reportDir);
        }
        try (Stream<Path> reports = Files.list(reportDir)) {
            return reports
                    .filter(Files::isRegularFile)
                    .filter(ConsolidatedReport::isReportFile)
                    .max(Comparator.comparingLong(PreviousRun::lastModifiedMillis))
                    .orElseThrow(() -> new PipelineException("No consolidated report found in " + reportDir));
        } catch (IOException e) {
            throw new PipelineException("Unable to list the consolidated reports in " + reportDir, e);
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new PipelineException("Unable to read the modification time of " + path, e);
        }
    }
}
