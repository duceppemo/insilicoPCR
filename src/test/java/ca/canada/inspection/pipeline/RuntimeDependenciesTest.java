package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The versions of the tools a run uses, for its QAlog.txt and report: asked of the programs, never failing a run. */
class RuntimeDependenciesTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @Test
    void aVersionIsReadFromWhatTheProgramPrints() {
        assertEquals(String.valueOf(Runtime.version().feature()),
                RuntimeDependencies.firstMatch(List.of(JAVA, "-version"), "version \"(\\d+)", 20));
    }

    @Test
    void aProgramThatCannotBeRunOrDoesNotSayIsUnknown() {
        assertEquals("unknown", RuntimeDependencies.firstMatch(List.of("no-such-program-insilicopcr"), "(.*)", 20));
        assertEquals("unknown", RuntimeDependencies.firstMatch(List.of(JAVA, "-version"), "blastn: ([0-9.]+)", 20));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aProgramThatDoesNotEndIsStoppedAndUnknown() {
        long start = System.nanoTime();
        assertEquals("unknown", RuntimeDependencies.firstMatch(List.of("sleep", "30"), "(.*)", 1));
        assertTrue(System.nanoTime() - start < 15_000_000_000L, "it waited for the program to end");
    }

    @Test
    void toolsThatAreNotThereAreUnknownNotAFailedRun() {
        RuntimeDependencies missing = new RuntimeDependencies(new AppPaths.RuntimeLayout(Path.of("no-such-root"),
                Path.of("no-such-root", "bbmap"), Path.of("no-such-root", "blast"), Path.of(JAVA)));
        assertTrue(missing.versions().startsWith("unknown ("), missing.versions());
    }
}
