package ca.canada.inspection.pipeline;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/** Writes a reproducibility log for one pipeline run. */
final class QaLogWriter {
    private static final DateTimeFormatter QA_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    void write(PipelineContext context, String version) {
        Path qLog = context.directories().output().resolve("QAlog.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(qLog, StandardCharsets.UTF_8)) {
            writeLine(writer, "In Silico PCR version: " + version);
            writeLine(writer, "Date run: " + QA_DATE_FORMAT.format(LocalDateTime.now()));
            writeLine(writer, "Run by user: " + System.getProperty("user.name"));
            writeLine(writer, "BBTools Location: " + context.dependencies().bbmapDirectory());
            writeLine(writer, "BLAST Location: " + context.dependencies().blastBinDirectory());
            writeLine(writer, "Output Folder: " + context.directories().output());
            writeLine(writer, "Primer File: " + context.config().primers());
            writeLine(writer, "Input File(s) :");

            Path input = context.config().input();
            if (Files.isDirectory(input)) {
                try (Stream<Path> files = Files.list(input)) {
                    for (Path file : files.sorted().toList()) {
                        writeLine(writer, file.toAbsolutePath().toString());
                    }
                }
            } else {
                writeLine(writer, input.toAbsolutePath().toString());
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
