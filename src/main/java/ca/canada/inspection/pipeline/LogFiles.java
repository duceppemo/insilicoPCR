package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.Dispatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class LogFiles {
    private LogFiles() {}

    /** Writes QAlog.txt for a run that stopped before writing its own (the window closed during the run). */
    public static void ensureQaLog(Path outDir, Path inputFile, Path primerFile, RuntimeDependencies dependencies) {
        if (!Files.exists(outDir.resolve("QAlog.txt"))) {
            QaLogWriter.write(outDir, Dispatcher.VERSION, dependencies.bbmapDirectory(),
                    dependencies.blastBinDirectory(), primerFile, inputFile, List.of());
        }
    }

    /** Appends the text of the application's log to log.txt. */
    public static void appendRunLog(Path outDir, String text) {
        var logPath = outDir.resolve("log.txt");
        try (var writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (var line : text.split("\\R")) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new PipelineException("Unable to append run log: " + logPath, e);
        }
    }
}
