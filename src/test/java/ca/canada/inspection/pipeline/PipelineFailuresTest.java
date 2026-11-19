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
    void theRunChecksItsPathsBeforeMakingTheOutputFolder() throws IOException {
        // On the command line, a primer file with a mistake left an empty output folder behind (the window checked
        // the paths first), and an output that is a file failed as "Unable to create directory".
        Path input = Files.createDirectories(directory.resolve("in"));
        Files.writeString(input.resolve("s.fasta"), ">c1\nACGTACGTACGTACGTACGT\n");
        Path badPrimers = Files.writeString(directory.resolve("primers.fasta"), ">only-F\nACGTACGTACGTACGTACGT\n");
        Path output = directory.resolve("out");

        PipelineException error = assertThrows(PipelineException.class,
                () -> new InSilicoPcrPipeline(PipelineConfig.builder(input, output, badPrimers).build(),
                        RunMonitor.CONSOLE).run());

        assertEquals(InputValidator.validate(input, output, badPrimers), error.getMessage());
        assertTrue(!Files.exists(output), "no output folder made");

        // A primer whose sequence is not bases: refused there too, not after the folder was made.
        Path notBases = Files.writeString(directory.resolve("xx.fasta"), ">a-F\nACGTXXACGTACGTACGTAC\n>a-R\nACGTACGTACGTACGTACGT\n");
        error = assertThrows(PipelineException.class,
                () -> new InSilicoPcrPipeline(PipelineConfig.builder(input, output, notBases).build(), RunMonitor.CONSOLE).run());
        assertTrue(error.getMessage().contains("not bases"), error.getMessage());
        assertTrue(!Files.exists(output), "no output folder made");

        Path aFile = Files.writeString(directory.resolve("taken"), "");
        Path primers = Files.writeString(directory.resolve("good.fasta"),
                ">a-F\nACGTACGTACGTACGTACGT\n>a-R\nACGTACGTACGTACGTACGT\n");
        error = assertThrows(PipelineException.class,
                () -> new InSilicoPcrPipeline(PipelineConfig.builder(input, aFile, primers).build(),
                        RunMonitor.CONSOLE).run());
        assertTrue(error.getMessage().startsWith("The output folder is a file: "), error.getMessage());
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

        PipelineException error = assertThrows(PipelineException.class, dependencies::bbtoolsClassPath);

        assertTrue(error.getMessage().startsWith("Missing BBMap runtime: "), error.getMessage());
        assertTrue(RunFailure.isForTheUser(error));
    }

    @Test
    void aPrimerDatabaseCountsAsWrittenOnlyWithItsFilesOfThisRun() throws IOException {
        Path database = directory.resolve("out space").resolve("primer_tmp.fasta");
        Files.createDirectories(database.getParent());
        long started = System.currentTimeMillis();
        assertTrue(!BlastDatabaseBuilder.written(database, started), "no files");
        for (String extension : List.of(".nin", ".nsq", ".nhr")) {
            Files.writeString(database.resolveSibling("primer_tmp.fasta" + extension), "x");
        }
        assertTrue(BlastDatabaseBuilder.written(database, started));
        // Files left by an earlier run do not count.
        assertTrue(!BlastDatabaseBuilder.written(database, started + 60_000));
    }
}
