package ca.canada.inspection.benchmark;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the benchmark: writes each scenario's input, runs the program on it, and scores what it reported.
 *
 * <pre>
 * scripts/benchmark/run.sh [--tier ci|standard|full] [--suite all|sites|geometry|reads] [--app &lt;release folder or jar&gt;]
 *                          [--out &lt;folder&gt;] [--threads n] [--baseline &lt;file&gt;] [--write-baseline &lt;file&gt;] [--keep]
 * scripts/benchmark/run.sh --concordance &lt;samples.tsv&gt; [--primers &lt;file&gt;] [--design n] [the options above but
 *                          --tier and --suite]
 * </pre>
 *
 * <p>The first form runs the synthetic cases ({@link Suites}); the second, real samples given as an assembly and as
 * its reads ({@link Concordance}).</p>
 *
 * <p>Exit code 0: done, and as the baseline when one is given; 1: worse than the baseline, or a run failed; 2: wrong
 * command line.</p>
 */
public final class Benchmark {

    private Benchmark() {
    }

    public static void main(String[] args) throws IOException {
        Suites.Tier tier = Suites.Tier.CI;
        String suite = "all";
        App app = App.currentBuild();
        Path out = Path.of("target", "benchmark");
        Path baseline = null;
        Path writeBaseline = null;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        boolean keep = false;
        Path concordance = null;
        Path primers = null;
        int design = 0;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--tier" -> tier = Suites.Tier.valueOf(args[++i].toUpperCase(java.util.Locale.ROOT));
                    case "--suite" -> suite = args[++i];
                    case "--app" -> app = App.installed(Path.of(args[++i]));
                    case "--out" -> out = Path.of(args[++i]);
                    case "--baseline" -> baseline = Path.of(args[++i]);
                    case "--write-baseline" -> writeBaseline = Path.of(args[++i]);
                    case "--threads" -> threads = Integer.parseInt(args[++i]);
                    case "--keep" -> keep = true;
                    case "--concordance" -> concordance = Path.of(args[++i]);
                    case "--primers" -> primers = Path.of(args[++i]);
                    case "--design" -> design = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("Unknown option " + args[i]);
                }
            }
            Suites.of(tier, suite);
            if (concordance != null && primers == null && design <= 0) {
                throw new IllegalArgumentException("--concordance needs --primers, --design, or both");
            }
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Options: --tier ci|standard|full, --suite all|sites|geometry|reads, --app <folder or jar>,"
                    + " --out <folder>, --threads <n>, --baseline <file>, --write-baseline <file>, --keep;"
                    + " --concordance <samples.tsv> with --primers <file> and/or --design <n>");
            System.exit(2);
            return;
        }
        Scorecard scorecard;
        String version = app.version();
        if (concordance != null) {
            scorecard = new Scorecard();
            delete(out.resolve("concordance"));
            Concordance.run(app, Concordance.manifest(concordance), primers, design, out, threads, System.out, scorecard);
            scorecard.write(out, version + ", concordance of " + concordance);
        } else {
            scorecard = run(app, Suites.of(tier, suite), out, threads, keep, System.out);
            scorecard.write(out, version + ", tier " + tier.name().toLowerCase(java.util.Locale.ROOT));
        }
        System.out.println();
        System.out.println(version);
        System.out.println(scorecard.summary());
        System.out.println("Scorecard: " + out.resolve("scorecard.tsv").toAbsolutePath());
        if (writeBaseline != null) {
            Files.writeString(writeBaseline, scorecard.baseline(), StandardCharsets.UTF_8);
            System.out.println("Baseline written: " + writeBaseline.toAbsolutePath());
        }
        boolean failed = scorecard.lines().stream().anyMatch(line -> line.result() == Scorecard.Result.FAILED);
        if (baseline != null) {
            Scorecard.Difference difference = scorecard.compare(Scorecard.readBaseline(baseline));
            System.out.println("Against " + baseline + ":");
            System.out.print(difference.text());
            Files.writeString(out.resolve("against-baseline.txt"), difference.text(), StandardCharsets.UTF_8);
            failed |= !difference.worse().isEmpty();
        }
        System.exit(failed ? 1 : 0);
    }

    /**
     * Runs {@code scenarios} with {@code app} under {@code work} and scores them.
     *
     * @param keep whether each scenario's input and the run's folder are kept (the run's log always is)
     */
    static Scorecard run(App app, List<Scenario> scenarios, Path work, int threads, boolean keep, PrintStream progress)
            throws IOException {
        Scorecard scorecard = new Scorecard();
        Files.createDirectories(work);
        for (Scenario scenario : scenarios) {
            Path folder = work.resolve(scenario.name);
            delete(folder);
            Files.createDirectories(folder);
            progress.printf("%s: %d sample(s), %d assay(s), %d case(s)...%n", scenario.name, scenario.samples.size(),
                    scenario.assays.size(), scenario.expectations.size());
            scenario.write(folder);
            Path log = work.resolve(scenario.name + ".log");
            App.Run run = app.run(folder.resolve("input"), folder.resolve("primers.fasta"), folder.resolve("output"),
                    scenario.options, threads, log);
            progress.printf("%s: exit %d in %.0f s%n", scenario.name, run.exitCode(), run.seconds());
            Observed observed = run.exitCode() == 0 ? Observed.of(run.output()) : null;
            scorecard.add(scenario, run, observed);
            if (!keep && run.exitCode() == 0) {
                delete(folder);
            }
        }
        return scorecard;
    }

    private static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path path : paths) {
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to delete " + path, e);
            }
        }
    }
}
