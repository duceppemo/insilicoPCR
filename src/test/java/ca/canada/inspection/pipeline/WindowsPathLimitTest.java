package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsPathLimitTest {

    @TempDir
    Path dir;

    private static Sample sample(String name, Sample.FileType type) {
        Sample sample = new Sample(name);
        sample.setFileType(type);
        return sample;
    }

    /** The longest of every path SampleFiles names: a file added there later is measured too. */
    private static String longestOf(SampleFiles files) throws ReflectiveOperationException {
        String longest = "";
        for (java.lang.reflect.Method method : SampleFiles.class.getDeclaredMethods()) {
            if (method.getReturnType() == Path.class && method.getParameterCount() == 0
                    && !method.getName().equals("directory")) {
                method.setAccessible(true);
                String path = ((Path) method.invoke(files)).toAbsolutePath().toString();
                if (path.length() > longest.length()) {
                    longest = path;
                }
            }
        }
        return longest;
    }

    @Test
    void theLongestFileOfARunIsMeasured() throws ReflectiveOperationException {
        // A deep output folder failed only once a program started: "CreateProcess error=267" or BLAST's
        // "File is not accessible", after the input check.
        PipelineDirectories directories = new PipelineDirectories(dir, dir.resolve("detailed_report"),
                dir.resolve("consolidated_report"));
        Sample reads = sample("isolate", Sample.FileType.FASTQ);
        String longest = longestOf(directories.sample("isolate"));

        assertDoesNotThrow(() -> WindowsPathLimit.check(directories, List.of(reads), longest.length() + 1));
        PipelineException error = assertThrows(PipelineException.class,
                () -> WindowsPathLimit.check(directories, List.of(reads), longest.length()));
        assertTrue(error.getMessage().contains(longest + " (" + longest.length() + " characters)"), error.getMessage());
        assertTrue(error.getMessage().contains("at least 1 characters shorter"), error.getMessage());
    }

    @Test
    void withoutASampleTheOutputFoldersOwnFilesAreMeasured() {
        // Checked before the input files are read, which can take minutes.
        PipelineDirectories directories = new PipelineDirectories(dir, dir.resolve("detailed_report"),
                dir.resolve("consolidated_report"));
        String database = directories.primerDatabase().toAbsolutePath() + ".nhr";

        assertDoesNotThrow(() -> WindowsPathLimit.check(directories, List.of(), database.length() + 1));
        assertThrows(PipelineException.class, () -> WindowsPathLimit.check(directories, List.of(), database.length()));
    }

    @Test
    void anAssemblyCountsItsReportAndItsDecompressedCopy() {
        PipelineDirectories directories = new PipelineDirectories(dir, dir.resolve("detailed_report"),
                dir.resolve("consolidated_report"));
        Sample assembly = sample("a", Sample.FileType.FASTA);
        String copy = dir.resolve("detailed_report").resolve("a").resolve(BlastQuery.LONGEST_NAME)
                .toAbsolutePath().toString();

        assertDoesNotThrow(() -> WindowsPathLimit.check(directories, List.of(assembly), copy.length() + 1));
        assertThrows(PipelineException.class,
                () -> WindowsPathLimit.check(directories, List.of(assembly), copy.length()));
    }
}
