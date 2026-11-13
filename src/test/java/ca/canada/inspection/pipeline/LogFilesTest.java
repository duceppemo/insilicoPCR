package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.dispatchpcr.Dispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The logs the application writes when its window closes. */
class LogFilesTest {

    @TempDir
    Path directory;

    @Test
    void aRunStoppedBeforeItsQaLogGetsOne() throws IOException {
        Path input = Files.writeString(directory.resolve("isolate.fasta"), ">c\nACGT\n");
        Path primers = directory.resolve("primers.fasta");

        LogFiles.ensureQaLog(directory, input, primers, dependencies());

        List<String> lines = Files.readAllLines(directory.resolve("QAlog.txt"));
        assertEquals("In Silico PCR version: " + Dispatcher.VERSION, lines.getFirst());
        assertTrue(lines.contains("Primer File: " + primers), lines.toString());
        assertEquals(input.toString(), lines.getLast());
    }

    @Test
    void theQaLogOfAFinishedRunIsKept() throws IOException {
        Path log = Files.writeString(directory.resolve("QAlog.txt"), "written by the run\n");

        LogFiles.ensureQaLog(directory, directory, directory.resolve("primers.fasta"), dependencies());

        assertEquals("written by the run\n", Files.readString(log));
    }

    @Test
    void theWindowsLogIsAppendedLineByLine() throws IOException {
        LogFiles.appendRunLog(directory, "first run\nDone in 3 seconds");
        LogFiles.appendRunLog(directory, "second run");

        assertEquals(List.of("first run", "Done in 3 seconds", "second run"),
                Files.readAllLines(directory.resolve("log.txt")));
    }

    private RuntimeDependencies dependencies() {
        return new RuntimeDependencies(new AppPaths.RuntimeLayout(directory, directory, directory,
                directory.resolve("bbmap"), directory.resolve("blast"), directory.resolve("java"), null));
    }
}
