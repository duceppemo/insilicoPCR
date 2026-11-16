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
            return new PreviousRun(latestReport(runDirectory, qaLog), legacySamples(qaLog));
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
                    if (name.matches() && Files.isRegularFile(file)) {
                        logs.put(name.group(1) == null ? 0 : Integer.parseInt(name.group(1)), file);
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

    /**
     * The report of a run folder whose log was written before 0.15.1: the newest consolidated
     * report no newer than the log, which that run wrote last (a newer one is from a run that did
     * not finish, maybe cut short). A folder copied without its dates has its files dated by the
     * copy, in any order: when no report is that old, the newest report.
     */
    static Path latestReport(Path runDirectory, Path qaLog) {
        Path reportDir = runDirectory.resolve(PipelineDirectories.CONSOLIDATED);
        if (!Files.isDirectory(reportDir)) {
            throw new PipelineException("Consolidated report folder not found: " + reportDir);
        }
        // A little slack for file systems that keep modification times coarsely.
        long notAfter = lastModifiedMillis(qaLog) + 2_000;
        try (Stream<Path> reports = Files.list(reportDir)) {
            List<Path> all = reports.filter(Files::isRegularFile).filter(ConsolidatedReport::isReportFile).toList();
            Comparator<Path> byDate = Comparator.comparingLong(PreviousRun::lastModifiedMillis);
            return all.stream().filter(report -> lastModifiedMillis(report) <= notAfter).max(byDate)
                    .or(() -> all.stream().max(byDate))
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
