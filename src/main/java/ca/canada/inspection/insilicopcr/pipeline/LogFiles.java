package ca.canada.inspection.insilicopcr.pipeline;

import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.insilicopcr.Methods;
import javafx.scene.control.TextArea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class LogFiles {
    private LogFiles() {}

    public static void ensureQaLog(Path outDir, Path inputFile, Path primerFile, DependencyContext dependencies) {
        var qaFile = outDir.resolve("QAlog.txt");
        if (!Files.exists(qaFile)) {
            Methods.makeQALog(qaFile, Dispatcher.version, outDir, inputFile, primerFile,
                    dependencies.bbtoolsLocation(), dependencies.blastLocation());
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
            throw new IllegalStateException("Unable to append run log: " + logPath, e);
        }
    }
}
