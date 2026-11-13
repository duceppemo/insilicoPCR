package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.insilicopcr.Methods;
import javafx.scene.control.TextArea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class LogFiles {
    private LogFiles() {}

    public static void ensureQaLog(Path outDir, Path inputFile, Path primerFile, DependencyContext dependencies) {
        var qaFile = outDir.resolve("QAlog.txt");
        if (!Files.exists(qaFile)) {
            Methods.makeQALog(qaFile, Dispatcher.VERSION, outDir, inputFile, primerFile,
                    dependencies.bbtoolsLocation(), dependencies.blastLocation());
        }
    }

    /** Writes QAlog.txt for the run that just finished, replacing any earlier one. */
    public static void writeQaLog(Path outDir, Path inputFile, Path primerFile, DependencyContext dependencies) {
        Methods.makeQALog(outDir.resolve("QAlog.txt"), Dispatcher.VERSION, outDir, inputFile, primerFile,
                dependencies.bbtoolsLocation(), dependencies.blastLocation());
    }

    /** Appends run-specific lines (e.g. FASTQ-mode diagnostics) to QAlog.txt. */
    public static void appendQaLog(Path outDir, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        var qaFile = outDir.resolve("QAlog.txt");
        try {
            // A blank line ends the input-file list; readers stop there (QaLogInputs).
            boolean endsWithNewline = !Files.exists(qaFile) || Files.size(qaFile) == 0
                    || Files.readString(qaFile, StandardCharsets.UTF_8).endsWith("\n");
            try (var writer = Files.newBufferedWriter(qaFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (!endsWithNewline) {
                    writer.newLine();
                }
                writer.newLine();
                for (var line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Unable to append to QA log: " + qaFile, e);
        }
    }

    public static void appendRunLog(Path outDir, TextArea outputField) {
        var logPath = outDir.resolve("log.txt");
        try (var writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (var line : outputField.getText().split("\\R")) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new PipelineException("Unable to append run log: " + logPath, e);
        }
    }
}
