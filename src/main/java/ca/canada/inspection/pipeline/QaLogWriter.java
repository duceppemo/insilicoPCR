package ca.canada.inspection.pipeline;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/** Writes a reproducibility log for one pipeline run. */
final class QaLogWriter {
    private static final DateTimeFormatter QA_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    /** @param extraLines run-specific lines appended after the input list (e.g. FASTQ-mode diagnostics) */
    void write(PipelineContext context, String version, List<String> extraLines) {
        write(context.directories().output(), version, context.dependencies().bbmapDirectory(),
                context.dependencies().blastBinDirectory(), context.config().primers(), context.config().input(),
                extraLines);
    }

    /** Writes {@code QAlog.txt} in {@code output}, replacing any earlier one. */
    static void write(Path output, String version, Path bbmapDirectory, Path blastBinDirectory, Path primers,
                      Path input, List<String> extraLines) {
        Path qLog = output.resolve("QAlog.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(qLog, StandardCharsets.UTF_8)) {
            writeLine(writer, "In Silico PCR version: " + version);
            writeLine(writer, "Date run: " + QA_DATE_FORMAT.format(LocalDateTime.now()));
            writeLine(writer, "Run by user: " + System.getProperty("user.name"));
            writeLine(writer, "BBTools Location: " + bbmapDirectory);
            writeLine(writer, "BLAST Location: " + blastBinDirectory);
            writeLine(writer, "Output Folder: " + output);
            writeLine(writer, "Primer File: " + primers);
            writeLine(writer, "Input File(s) :");

            if (Files.isDirectory(input)) {
                try (Stream<Path> files = Files.list(input)) {
                    for (Path file : files.sorted().toList()) {
                        writeLine(writer, file.toAbsolutePath().toString());
                    }
                }
            } else {
                writeLine(writer, input.toAbsolutePath().toString());
            }
            if (!extraLines.isEmpty()) {
                // A blank line ends the input-file list; readers stop there (QaLogInputs).
                writeLine(writer, "");
                for (String line : extraLines) {
                    writeLine(writer, line);
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write QA log: " + qLog, e);
        }
    }

    private static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
    }
}
