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

        PipelineException error = assertThrows(PipelineException.class, () -> RunDirectories.createUnder(notAFolder));

        assertEquals("Unable to create output directories under " + notAFolder, error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void aQaLogThatCannotBeWrittenIsForTheUser() throws IOException {
        Path notAFolder = Files.writeString(directory.resolve("results"), "a file where the folder should go");

        PipelineException error = assertThrows(PipelineException.class,
                () -> LogFiles.appendQaLog(notAFolder, List.of("FASTQ mode Tadpole settings: k=31")));

        assertEquals("Unable to append to QA log: " + notAFolder.resolve("QAlog.txt"), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void aMissingBbtoolsJarIsForTheUser() {
        DependencyContext dependencies = new DependencyContext(directory, directory, directory, "java", null);

        PipelineException error = assertThrows(PipelineException.class, dependencies::bbtoolsJar);

        assertTrue(error.getMessage().startsWith("Missing BBMap runtime: "), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }
}
