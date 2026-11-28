package ca.canada.inspection.benchmark;

import ca.canada.inspection.pipeline.AppPaths;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the benchmark's quick tier against this build and compares it with the baseline kept in the repository
 * ({@code benchmark/baselines/ci.tsv}): a change that makes a case worse fails the build, and so does one that makes
 * a case better until the baseline says so, so that the baseline is always what the program does. Exclude with
 * {@code -DexcludedGroups=benchmark} for a quick build.
 *
 * <p>After a change that is meant to move cases: {@code scripts/benchmark/run.sh --write-baseline
 * benchmark/baselines/ci.tsv}, and read the difference before committing it.</p>
 */
@Tag("benchmark")
class BenchmarkTest {

    private static final Path BASELINE = Path.of("benchmark", "baselines", "ci.tsv");

    @TempDir
    Path work;

    @Test
    void theQuickTierGivesTheBaseline() throws IOException {
        requireTools();
        Scorecard scorecard;
        try (PrintStream quiet = new PrintStream(PrintStream.nullOutputStream())) {
            scorecard = Benchmark.run(App.currentBuild(), Suites.of(Suites.Tier.CI, "all"), work, 4, false, quiet);
        }
        Scorecard.Difference difference = scorecard.compare(Scorecard.readBaseline(BASELINE));

        assertTrue(scorecard.lines().stream().noneMatch(line -> line.result() == Scorecard.Result.FAILED),
                "a run of the benchmark failed:\n" + scorecard.summary());
        assertTrue(difference.worse().isEmpty(), "worse than " + BASELINE + ":\n" + difference.text());
        assertTrue(difference.better().isEmpty(), "better than " + BASELINE + ": write the baseline again"
                + " (scripts/benchmark/run.sh --write-baseline " + BASELINE + ")\n" + difference.text());
    }

    /** As the end-to-end tests: skipped without the bundled tools (a checkout without Git LFS). */
    private static void requireTools() {
        AppPaths.RuntimeLayout layout;
        try {
            layout = AppPaths.discover();
        } catch (RuntimeException e) {
            assumeTrue(false, "BLAST+ and BBTools are not in runtime/: " + e.getMessage());
            return;
        }
        assumeTrue(size(layout.blastnExecutable()) > 100_000, "blastn is a Git LFS pointer");
        assumeTrue(size(layout.bbmapDirectory().resolve("bbtools.jar")) > 100_000, "bbtools.jar is a Git LFS pointer");
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }
}
