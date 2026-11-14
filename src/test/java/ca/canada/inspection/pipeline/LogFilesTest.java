package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** log.txt, which the application appends to at the end of each run. */
class LogFilesTest {

    @TempDir
    Path directory;

    @Test
    void theWindowsLogIsAppendedLineByLine() throws IOException {
        LogFiles.appendRunLog(directory, "first run\nDone in 3 seconds");
        LogFiles.appendRunLog(directory, "second run");

        assertEquals(List.of("first run", "Done in 3 seconds", "second run"),
                Files.readAllLines(directory.resolve("log.txt")));
    }
}
