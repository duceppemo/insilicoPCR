package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** makeblastdb and blastn are stood in for by shell scripts, so these run where /bin/sh does. */
@DisabledOnOs(OS.WINDOWS)
class BlastDatabaseBuilderTest {

    /** Writes the three database files named by -out, its 7th argument. */
    private static final String WRITES_THE_DATABASE = "touch \"$7.nin\" \"$7.nsq\" \"$7.nhr\"\n";

    @TempDir
    Path dir;

    /** Collects the messages of a run. */
    private static final class Messages implements RunMonitor {
        final List<String> info = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {
            info.add(message);
        }

        @Override
        public void warning(String message) {
            warnings.add(message);
        }
    }

    private PipelineContext context(String folder, Messages messages, String makeblastdb, boolean executable)
            throws IOException {
        Path output = Files.createDirectories(dir.resolve(folder));
        Path blast = Files.createDirectories(output.resolve("blast"));
        script(blast.resolve("blastn"), "exit 0\n", true);
        script(blast.resolve("makeblastdb"), makeblastdb, executable);
        PipelineContext context = TestContexts.writingTo(output, Map.of());
        return new PipelineContext(context.config(), context.dependencies(), context.directories(), context.samples(),
                context.primers(), context.childJavaMemoryBudgetGiB(), context.inputIssues(), messages);
    }

    private static void script(Path file, String body, boolean executable) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(executable ? "rwxr-xr-x" : "rw-r--r--"));
    }

    @Test
    void theDatabaseIsBuiltInItsFolderUnderItsBareName() throws IOException {
        Messages messages = new Messages();
        PipelineContext context = context("out", messages,
                "[ \"$5\" = primer_tmp.fasta ] && [ \"$7\" = primer_tmp.fasta ] || exit 3\n" + WRITES_THE_DATABASE, true);

        BlastDatabaseBuilder.build(context);

        assertTrue(BlastDatabaseBuilder.written(context.directories().primerDatabase(), 0));
        assertEquals(List.of(), messages.warnings);
    }

    @Test
    void theDatabaseIsAVersion4DatabaseWithoutItsLmdbIndex() throws IOException {
        // makeblastdb on Windows cannot create the LMDB index of a version 5 database in a folder whose
        // path has a letter outside ASCII ("Résultats"): every run into such a folder failed.
        Messages messages = new Messages();
        PipelineContext context = context("résultats", messages,
                "[ \"$8\" = -blastdb_version ] && [ \"$9\" = 4 ] || exit 3\n" + WRITES_THE_DATABASE, true);

        BlastDatabaseBuilder.build(context);

        assertTrue(BlastDatabaseBuilder.written(context.directories().primerDatabase(), 0));
    }

    @Test
    void theDatabaseOfAnEarlierRunIsDeletedFirst() throws IOException {
        // Its version 5 files (.njs, .ndb) are not replaced by a version 4 database: blastn would read the
        // earlier run's primers from them.
        Messages messages = new Messages();
        PipelineContext context = context("out", messages, WRITES_THE_DATABASE, true);
        Path database = context.directories().primerDatabase();
        Files.writeString(database, ">a-F\nACGT\n");
        for (String extension : List.of(".njs", ".ndb", ".nin", ".not")) {
            Files.writeString(database.resolveSibling(database.getFileName() + extension), "old");
        }

        BlastDatabaseBuilder.build(context);

        assertFalse(Files.exists(database.resolveSibling(database.getFileName() + ".njs")));
        assertFalse(Files.exists(database.resolveSibling(database.getFileName() + ".ndb")));
        assertTrue(Files.exists(database), "the primers themselves are kept");
        assertTrue(BlastDatabaseBuilder.written(database, 0));
    }

    @Test
    void aFailedCheckOfACompleteDatabaseInAFolderWithASpaceIsANote() throws IOException {
        Messages messages = new Messages();
        PipelineContext context = context("results with space", messages, WRITES_THE_DATABASE + "exit 1\n", true);

        BlastDatabaseBuilder.build(context);

        assertTrue(messages.info.stream().anyMatch(line -> line.startsWith("Note: makeblastdb's own check")),
                messages.info::toString);
    }

    @Test
    void aFailureIsAnErrorWithoutASpaceOrWithoutTheDatabase() throws IOException {
        PipelineContext noSpace = context("out", new Messages(), WRITES_THE_DATABASE + "exit 1\n", true);
        assertThrows(ProgramFailedException.class, () -> BlastDatabaseBuilder.build(noSpace));

        PipelineContext nothingWritten = context("with space", new Messages(), "exit 1\n", true);
        assertThrows(ProgramFailedException.class, () -> BlastDatabaseBuilder.build(nothingWritten));
    }

    @Test
    void aProgramThatLostItsModeIsMadeExecutable() throws IOException {
        Messages messages = new Messages();
        PipelineContext context = context("out", messages, WRITES_THE_DATABASE, false);

        BlastDatabaseBuilder.build(context);

        assertTrue(Files.isExecutable(context.dependencies().makeblastdb()));
        assertEquals(List.of(), messages.warnings);
    }

    @Test
    void aProgramThatCannotBeMadeExecutableIsAWarning() throws IOException {
        Messages messages = new Messages();
        PipelineContext context = context("out", messages, WRITES_THE_DATABASE, true);
        Files.delete(context.dependencies().blastn());

        BlastDatabaseBuilder.build(context);

        assertEquals(1, messages.warnings.size(), messages.warnings::toString);
        assertTrue(messages.warnings.getFirst().contains("is not executable and could not be made so"));
    }

    @Test
    void databaseFilesLeftByAnEarlierRunAreNotThisRunsDatabase() throws IOException {
        Path database = Files.createDirectories(dir.resolve("db")).resolve("primer_tmp.fasta");
        for (String extension : List.of(".nin", ".nsq", ".nhr")) {
            Files.setLastModifiedTime(Files.createFile(database.resolveSibling("primer_tmp.fasta" + extension)),
                    FileTime.fromMillis(1_000_000));
        }

        assertTrue(BlastDatabaseBuilder.written(database, 1_000_000));
        assertFalse(BlastDatabaseBuilder.written(database, 1_000_000 + 60_000));
        Files.delete(database.resolveSibling("primer_tmp.fasta.nsq"));
        assertFalse(BlastDatabaseBuilder.written(database, 0));
    }
}
