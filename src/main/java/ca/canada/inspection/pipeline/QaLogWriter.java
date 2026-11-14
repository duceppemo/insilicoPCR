package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Writes {@code QAlog.txt}, the record of a finished run: how it was run, on which files,
 * which samples it analysed and which report it wrote. {@link PreviousRun} reads the last
 * two back to reopen the run.
 */
final class QaLogWriter {
    private static final DateTimeFormatter QA_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    /** Starts the line naming the run's consolidated report, relative to the output folder. */
    static final String REPORT_PREFIX = "Consolidated report: ";
    /** Starts each line naming a sample the run analysed (one gel lane each). */
    static final String SAMPLE_PREFIX = "Sample analysed: ";

    private QaLogWriter() {
    }

    /**
     * Writes {@code QAlog.txt} in the output folder, replacing any earlier one.
     *
     * @param report     the consolidated report the run wrote
     * @param extraLines run-specific lines appended at the end (input issues, FASTQ-mode diagnostics)
     */
    static void write(PipelineContext context, String version, Path report, List<String> extraLines) {
        Path output = context.directories().output();
        Path qLog = output.resolve("QAlog.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(qLog, StandardCharsets.UTF_8)) {
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
            writeLine(writer, "Input File(s) :");
            for (Path file : context.samples().values().stream().flatMap(sample -> sample.files().stream())
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                writeLine(writer, file.toAbsolutePath().toString());
            }
            // A blank line ends the input-file list; readers before 0.15.1 stop there.
            writeLine(writer, "");
            for (Sample sample : context.samples().values()) {
                writeLine(writer, SAMPLE_PREFIX + sample.getName());
            }
            for (String line : extraLines) {
                writeLine(writer, line);
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write QA log: " + qLog, e);
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
}
