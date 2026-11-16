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
import java.util.stream.Stream;

/**
 * A finished run, reopened from its output folder: the report it wrote and the samples it
 * analysed, as its {@code QAlog.txt} records them.
 *
 * <p>The log names both ({@link QaLogWriter#REPORT_PREFIX}, {@link QaLogWriter#SAMPLE_PREFIX}),
 * since 0.15.1. An older log names neither, and its run is not reopened: 1.0 left out the code
 * that worked them out from its input list.</p>
 *
 * @param report  the run's consolidated report
 * @param samples the samples it analysed: one gel lane each, with or without a band
 */
public record PreviousRun(Path report, List<String> samples) {
    public PreviousRun {
        samples = List.copyOf(samples);
    }

    /**
     * The latest finished run of {@code runDirectory} whose report is still there: the one whose log
     * has the highest number ({@code QAlog(2).txt} after {@code QAlog(1).txt} after {@code QAlog.txt}),
     * as its report has, or the one before it when that report was deleted.
     */
    public static PreviousRun open(Path runDirectory) {
        List<Path> logs = logsLatestFirst(runDirectory);
        if (logs.isEmpty()) {
            throw new PipelineException("QAlog.txt not found in " + runDirectory
                    + ": not the output folder of a finished run");
        }
        PipelineException latestMissing = null;
        for (Path qaLog : logs) {
            try {
                return open(runDirectory, qaLog);
            } catch (ReportMissing e) {
                latestMissing = latestMissing == null ? e : latestMissing;
            }
        }
        throw latestMissing;
    }

    /** A log whose report is no longer in the folder. */
    private static final class ReportMissing extends PipelineException {
        ReportMissing(Path runDirectory, Path report) {
            super("The report of the run in " + runDirectory + " is missing: " + report);
        }
    }

    private static PreviousRun open(Path runDirectory, Path qaLog) {
        List<String> lines;
        try {
            lines = Files.readAllLines(qaLog, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PipelineException("Unable to read " + qaLog, e);
        }
        Optional<String> reportLine = valueOf(lines, QaLogWriter.REPORT_PREFIX).stream().findFirst();
        if (reportLine.isEmpty()) {
            throw new PipelineException(qaLog + " was written by a version before 0.15.1, whose runs this version"
                    + " does not reopen: open its report (consolidated_report/report.html) in a browser, or run it again");
        }
        Path report = runDirectory.resolve(reportLine.get());
        if (!Files.isRegularFile(report)) {
            throw new ReportMissing(runDirectory, report);
        }
        return new PreviousRun(report, valueOf(lines, QaLogWriter.SAMPLE_PREFIX));
    }

    /** The logs in {@code runDirectory}, the highest number first. */
    static List<Path> logsLatestFirst(Path runDirectory) {
        java.util.TreeMap<Integer, Path> logs = new java.util.TreeMap<>(Comparator.reverseOrder());
        if (Files.isDirectory(runDirectory)) {
            try (Stream<Path> files = Files.list(runDirectory)) {
                for (Path file : files.toList()) {
                    java.util.regex.Matcher name = QaLogWriter.FILE_PATTERN.matcher(file.getFileName().toString());
                    int number = !name.matches() ? -1 : name.group(1) == null ? 0
                            : ConsolidatedReport.number("(" + name.group(1) + ")");
                    if (number >= 0 && Files.isRegularFile(file)) {
                        logs.put(number, file);
                    }
                }
            } catch (IOException | NumberFormatException e) {
                throw new PipelineException("Unable to list the logs in " + runDirectory, e);
            }
        }
        return List.copyOf(logs.values());
    }

    private static List<String> valueOf(List<String> lines, String prefix) {
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                // As written: a sample named "Sample 1 " (from Sample 1 _R1.fastq.gz) is not "Sample 1",
                // whose empty lane stood beside the one holding its bands.
                values.add(line.substring(prefix.length()));
            }
        }
        return values;
    }
}
