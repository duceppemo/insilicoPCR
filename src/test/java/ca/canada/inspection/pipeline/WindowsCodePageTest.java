package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsCodePageTest {

    @Test
    void aLetterOutsideTheCodePageIsNamedBeforeAnyProgramRuns() {
        // From the application, an output folder "数据 out" failed only at makeblastdb, with "exit code 2".
        Charset windows1252 = Charset.forName("windows-1252");
        Path accented = Path.of("Résultats (été)", "out");
        Path chinese = Path.of("数据 Δ out");

        assertDoesNotThrow(() -> WindowsCodePage.check(windows1252, List.of(accented)));
        PipelineException error = assertThrows(PipelineException.class,
                () -> WindowsCodePage.check(windows1252, List.of(accented, chinese)));
        assertTrue(error.getMessage().contains("has a letter (数据Δ)"), error.getMessage());
        assertTrue(error.getMessage().contains("(windows-1252)"), error.getMessage());
        assertDoesNotThrow(() -> WindowsCodePage.check(Charset.forName("UTF-8"), List.of(chinese)),
                "the UTF-8 system locale holds every letter");
    }

    @Test
    void thePrimerFileIsNotGivenToAnyProgram() {
        // insilicoPCR reads the primer file and writes primer_tmp.fasta into the output folder for
        // makeblastdb: a primer file in a folder the code page cannot name runs all the same.
        ca.canada.inspection.model.Sample sample = new ca.canada.inspection.model.Sample("s");
        sample.addFile(Path.of("in", "s.fasta"));

        List<Path> paths = WindowsCodePage.givenToPrograms(Path.of("out"), Path.of("app"), List.of(sample));

        assertTrue(paths.containsAll(List.of(Path.of("out"), Path.of("app"), Path.of("in", "s.fasta"))), paths.toString());
        assertDoesNotThrow(() -> WindowsCodePage.check(Charset.forName("windows-1252"), paths));
    }
}
