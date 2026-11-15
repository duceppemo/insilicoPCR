package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OpenElsewhereTest {

    @TempDir
    Path dir;

    @Test
    void aFileHeldByAnotherProgramIsSaidToBe() {
        String held = OpenElsewhere.describe(new FileSystemException("C:\\out\\s.tsv", null,
                "The process cannot access the file because it is being used by another process"));
        assertTrue(held.startsWith("it is open in another program"), held);
        assertTrue(OpenElsewhere.describe(new AccessDeniedException("s.tsv")).startsWith("it is open in another program"));
        assertEquals("Disk full", OpenElsewhere.describe(new IOException("Disk full")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aReportOpenInExcelStopsTheSearchSayingSo() throws IOException {
        // Excel holds the file it shows without letting it be deleted: blastn said "File is not accessible".
        Path report = Files.writeString(dir.resolve("s.tsv"), "old");
        RunMonitor noProgram = new RunMonitor() {
            @Override
            public void runProgram(Path directory, List<String> command, String program) {
                fail("blastn should not have been started");
            }
        };
        try (RandomAccessFile held = new RandomAccessFile(report.toFile(), "rw")) {
            PipelineException error = assertThrows(PipelineException.class, () -> BlastTsv.runBlastn(noProgram,
                    dir.resolve("blastn.exe"), dir.resolve("q.fasta"), dir.resolve("db"), 10, 1, report));
            assertTrue(error.getMessage().contains("it is open in another program"), error.getMessage());
        }
    }
}
