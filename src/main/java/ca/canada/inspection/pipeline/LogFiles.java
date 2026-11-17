package ca.canada.inspection.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** {@code log.txt}: what the application's window showed of each run, appended run after run. */
public final class LogFiles {
    private LogFiles() {}

    /** Appends the text of the application's log to log.txt. */
    public static void appendRunLog(Path outDir, String text) {
        var logPath = outDir.resolve("log.txt");
        try (var writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (var line : text.split("\\R")) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException e) {
            throw new PipelineException("Unable to append run log: " + logPath, e);
        }
    }
}
