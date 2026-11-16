package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.util.InputCheck;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code QAlog.txt}, the record of a finished run: how it was run, on which files,
 * which samples it analysed and which report it wrote. {@link PreviousRun} reads the last
 * two back to reopen the run.
 */
final class QaLogWriter {
    private static final DateTimeFormatter QA_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    /** The log of a folder's first run, which marks a finished run; the others are {@link #fileFor}. */
    static final String FILE_NAME = "QAlog.txt";

    /** The logs a run folder may hold: {@code QAlog.txt}, {@code QAlog(1).txt} and on. */
    static final java.util.regex.Pattern FILE_PATTERN = java.util.regex.Pattern.compile("QAlog(?:\\((\\d+)\\))?\\.txt");

    /**
     * The log of the run that wrote {@code report}: {@code QAlog.txt} for {@code report.tsv},
     * {@code QAlog(1).txt} for {@code report(1).tsv}. Each report keeps its own record: a second
     * run into a folder replaced the first one's, and the first report could no longer be reopened.
     */
    static String fileFor(Path report) {
        String base = ca.canada.inspection.report.ConsolidatedReport.baseName(report);
        return "QAlog" + (base.startsWith("report") ? base.substring("report".length()) : "") + ".txt";
    }

    /** Starts the line naming the run's consolidated report, relative to the output folder. */
    static final String REPORT_PREFIX = "Consolidated report: ";
    /** Starts each line naming a sample the run analysed (one gel lane each). */
    static final String SAMPLE_PREFIX = "Sample analysed: ";
    /** Starts the list of the run's input files. */
    static final String INPUTS_MARKER = "Input File(s) :";
    /** Starts the line that names a sample left out for a faulty input file (since 0.13.0). */
    static final String NOT_ANALYSED_PREFIX = "Sample not analysed: ";

    private QaLogWriter() {
    }

    /**
     * Writes the log of the run that wrote {@code report} in the output folder ({@link #fileFor}).
     *
     * @param report     the consolidated report the run wrote
     * @param extraLines run-specific lines appended at the end (input issues, FASTQ-mode diagnostics)
     */
    static void write(PipelineContext context, String version, Path report, List<String> extraLines) {
        Path output = context.directories().output();
        Path qLog = output.resolve(fileFor(report));
        // Written beside it, then moved over it: a write that stops partway (a full disk, the
        // program ending) leaves the last finished run's log whole, not half of this one.
        Path written = qLog.resolveSibling(qLog.getFileName() + ".part");
        try (BufferedWriter writer = Files.newBufferedWriter(written, StandardCharsets.UTF_8)) {
            writeLine(writer, "In Silico PCR version: " + version);
            writeLine(writer, "Date run: " + QA_DATE_FORMAT.format(LocalDateTime.now()));
            writeLine(writer, "Run by user: " + System.getProperty("user.name"));
            writeLine(writer, "BBTools Location: " + context.dependencies().bbmapDirectory());
            writeLine(writer, "BLAST Location: " + context.dependencies().blastBinDirectory());
            writeLine(writer, "Output Folder: " + output);
            writeLine(writer, "Primer File: " + context.config().primers());
            writeLine(writer, REPORT_PREFIX + relative(output, report));
            // The files of the samples analysed: a folder may hold other files, and files may be
            // added to it after the run.
            writeLine(writer, INPUTS_MARKER);
            for (Path file : context.samples().values().stream().flatMap(sample -> sample.files().stream())
                    .toList()) {
                writeLine(writer, file.toAbsolutePath().toString());
            }
            // A blank line ends the input-file list; readers before 0.15.1 stop there.
            writeLine(writer, "");
            for (Sample sample : context.samples().values()) {
                writeLine(writer, SAMPLE_PREFIX + sample.getName());
            }
            for (String line : RunSettings.lines(context.config(), context.hasFastqSamples())) {
                writeLine(writer, line);
            }
            writeLine(writer, "  Tools: " + context.dependencies().versions());
            for (String line : extraLines) {
                writeLine(writer, line);
            }
            writer.close();
            try {
                Files.move(written, qLog, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(written, qLog, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(written);
            throw new PipelineException("Could not write QA log: " + qLog, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The failure to write the log is what the run reports.
        }
    }

    private static String relative(Path output, Path report) {
        Path absoluteOutput = output.toAbsolutePath().normalize();
        Path absoluteReport = report.toAbsolutePath().normalize();
        return absoluteReport.startsWith(absoluteOutput)
                ? absoluteOutput.relativize(absoluteReport).toString().replace('\\', '/')
                : absoluteReport.toString();
    }

    private static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
    }

    /** What QAlog.txt says about the input that was left out; empty when nothing was. */
    static List<String> inputIssueLines(List<InputCheck.Issue> issues) {
        if (issues.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> bySample = new LinkedHashMap<>();
        issues.forEach(issue -> bySample.computeIfAbsent(issue.sample(), ignored -> new ArrayList<>())
                .add(issue.describe()));
        List<String> lines = new ArrayList<>();
        lines.add("Input not analysed: a file of these samples is faulty, and nothing was run on them.");
        bySample.forEach((sample, problems) -> {
            lines.add(NOT_ANALYSED_PREFIX + sample);
            problems.forEach(problem -> lines.add("  " + problem));
        });
        lines.add("");
        return lines;
    }
}
