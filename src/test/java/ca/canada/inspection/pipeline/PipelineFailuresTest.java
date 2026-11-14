package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Failures of a run that the user can act on are raised as such, so they are shown as a message. */
class PipelineFailuresTest {

    @TempDir
    Path directory;

    @Test
    void anOutputFolderThatCannotBeCreatedIsForTheUser() throws IOException {
        Path notAFolder = Files.writeString(directory.resolve("results"), "a file where the folder should go");

        PipelineException error = assertThrows(PipelineException.class, () -> PipelineDirectories.create(notAFolder));

        assertEquals("Unable to create directory: " + notAFolder.resolve("detailed_report"), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void aQaLogThatCannotBeWrittenIsForTheUser() throws IOException {
        Path notAFolder = Files.writeString(directory.resolve("results"), "a file where the folder should go");

        PipelineContext context = TestContexts.writingTo(notAFolder, java.util.Map.of());
        PipelineException error = assertThrows(PipelineException.class, () -> QaLogWriter.write(context, "0.0.0",
                notAFolder.resolve("report.tsv"), List.of()));

        assertEquals("Could not write QA log: " + notAFolder.resolve("QAlog.txt"), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void aQaLogThatCannotReplaceTheOldOneLeavesNoPartialFile() throws IOException {
        // A folder (not empty) where QAlog.txt goes: the log is written beside it, then cannot be moved.
        Path output = Files.createDirectories(directory.resolve("results"));
        Files.writeString(Files.createDirectories(output.resolve("QAlog.txt")).resolve("keep"), "");
        PipelineContext context = TestContexts.writingTo(output, java.util.Map.of());

        PipelineException error = assertThrows(PipelineException.class, () -> QaLogWriter.write(context, "0.0.0",
                output.resolve("report.tsv"), List.of()));

        assertEquals("Could not write QA log: " + output.resolve("QAlog.txt"), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
        assertTrue(!Files.exists(output.resolve("QAlog.txt.part")), "the partial log is deleted");
    }

    @Test
    void aRunLogThatCannotBeWrittenIsForTheUser() throws IOException {
        Path notAFolder = Files.writeString(directory.resolve("results"), "a file where the folder should go");

        PipelineException error = assertThrows(PipelineException.class,
                () -> LogFiles.appendRunLog(notAFolder, "Beginning Program Run"));

        assertEquals("Unable to append run log: " + notAFolder.resolve("log.txt"), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void aMissingBbtoolsJarIsForTheUser() {
        RuntimeDependencies dependencies = new RuntimeDependencies(new AppPaths.RuntimeLayout(directory, directory,
                directory, directory.resolve("java")));

        PipelineException error = assertThrows(PipelineException.class, dependencies::bbtoolsJar);

        assertTrue(error.getMessage().startsWith("Missing BBMap runtime: "), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }
}
