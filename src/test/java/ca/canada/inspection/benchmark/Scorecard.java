package ca.canada.inspection.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The benchmark's result: one line for each case, saying whether the program reported what the case's sample must
 * show, and what it said when it did not.
 */
final class Scorecard {

    enum Result {
        /** The expected products, and no other. */
        FOUND(true),
        /** No product where none is expected. */
        ABSENT(true),
        /** No product where one is expected. */
        MISSED(false),
        /** Products, but not the expected ones: another size, one too many, one too few. */
        WRONG(false),
        /** A product where none is expected. */
        UNEXPECTED(false),
        /** The run did not finish. */
        FAILED(false);

        final boolean correct;

        Result(boolean correct) {
            this.correct = correct;
        }
    }

    /**
     * One case.
     *
     * @param key      what a baseline is compared by: the suite and the case's name
     * @param expected the expected product sizes, or {@code none}
     * @param observed the reported product sizes, or {@code none}
     * @param said     what the run said of the sample and assay beside its products
     */
    record Line(String key, String suite, String scenario, String category, String sample, String assay,
                String expected, String observed, Result result, boolean scored, String said, String note) {
    }

    /** A run's time. */
    record Timing(String suite, String scenario, int samples, int assays, double seconds, int exitCode) {
    }

    private final List<Line> lines = new ArrayList<>();
    private final List<Timing> timings = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();

    List<Line> lines() {
        return lines;
    }

    List<Timing> timings() {
        return timings;
    }

    /** Scores {@code scenario} against what its run reported; {@code observed} is {@code null} for a run that failed. */
    void add(Scenario scenario, App.Run run, Observed observed) {
        timings.add(new Timing(scenario.suite, scenario.name, scenario.samples.size(), scenario.assays.size(),
                run.seconds(), run.exitCode()));
        boolean reads = scenario.hasReads();
        Set<Observed.Key> expected = new HashSet<>();
        for (Scenario.Expectation expectation : scenario.expectations) {
            Observed.Key key = new Observed.Key(expectation.sample(), expectation.assay());
            expected.add(key);
            List<Integer> sizes = observed == null ? List.of()
                    : observed.products(key).stream().map(Observed.Product::size).toList();
            Result result = observed == null ? Result.FAILED : result(expectation.sizes(), sizes, reads);
            add(new Line(scenario.suite + "/" + expectation.caseId(), scenario.suite, scenario.name,
                    expectation.category(), expectation.sample(), expectation.assay(), sizes(expectation.sizes()),
                    sizes(sizes), result, expectation.scored(), observed == null ? "run failed, exit "
                    + run.exitCode() : observed.said(key), expectation.note()));
        }
        if (observed == null) {
            return;
        }
        // A sample and assay the scenario does not list must give nothing.
        for (Observed.Key key : observed.withProducts()) {
            if (!expected.contains(key)) {
                add(new Line(scenario.suite + "/unlisted:" + scenario.name + ":" + key.sample() + ":" + key.assay(),
                        scenario.suite, scenario.name, "unlisted", key.sample(), key.assay(), "none",
                        sizes(observed.products(key).stream().map(Observed.Product::size).toList()),
                        Result.UNEXPECTED, true, observed.said(key), "no product is expected of an assay in a sample"
                        + " the scenario does not name"));
            }
        }
    }

    void add(Line line) {
        if (!keys.add(line.key())) {
            throw new IllegalStateException("Two cases named " + line.key());
        }
        lines.add(line);
    }

    /**
     * Compares the sizes reported with those expected. A product is one size: on reads, copies of one size are one
     * call, so sizes are compared once each; on an assembly, as many times as there are products.
     */
    static Result result(List<Integer> expected, List<Integer> observed, boolean reads) {
        if (expected.isEmpty()) {
            return observed.isEmpty() ? Result.ABSENT : Result.UNEXPECTED;
        }
        if (observed.isEmpty()) {
            return Result.MISSED;
        }
        List<Integer> wanted = reads ? expected.stream().distinct().sorted().toList() : expected.stream().sorted().toList();
        List<Integer> got = reads ? observed.stream().distinct().sorted().toList() : observed.stream().sorted().toList();
        return wanted.equals(got) ? Result.FOUND : Result.WRONG;
    }

    static String sizes(List<Integer> sizes) {
        return sizes.isEmpty() ? "none" : sizes.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** Writes every line as {@code scorecard.tsv}, the timings and the summary into {@code folder}. */
    void write(Path folder, String app) throws IOException {
        Files.createDirectories(folder);
        StringBuilder card = new StringBuilder("Case\tSuite\tScenario\tCategory\tSample\tAssay\tExpected\tObserved"
                + "\tResult\tScored\tSaid\tNote\n");
        for (Line line : sorted()) {
            card.append(String.join("\t", line.key(), line.suite(), line.scenario(), line.category(), line.sample(),
                    line.assay(), line.expected(), line.observed(), line.result().name(), line.scored() ? "yes" : "no",
                    line.said(), line.note())).append('\n');
        }
        Files.writeString(folder.resolve("scorecard.tsv"), card, StandardCharsets.UTF_8);
        StringBuilder times = new StringBuilder("Suite\tScenario\tSamples\tAssays\tSeconds\tExitCode\n");
        for (Timing timing : timings) {
            times.append(String.join("\t", timing.suite(), timing.scenario(), String.valueOf(timing.samples()),
                    String.valueOf(timing.assays()), String.format(Locale.ROOT, "%.1f", timing.seconds()),
                    String.valueOf(timing.exitCode()))).append('\n');
        }
        Files.writeString(folder.resolve("timings.tsv"), times, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("summary.txt"), app + "\n\n" + summary(), StandardCharsets.UTF_8);
    }

    List<Line> sorted() {
        return lines.stream().sorted(java.util.Comparator.comparing(Line::key)).toList();
    }

    /**
     * The scored cases counted by suite and category: of the cases that expect a product, how many were found; of
     * those that expect none, how many gave none; and what the run said of the products it missed.
     */
    String summary() {
        record Count(int[] numbers, Map<String, Integer> said) {
        }
        Map<String, Count> counts = new LinkedHashMap<>();
        for (Line line : lines) {
            Count count = counts.computeIfAbsent(line.suite() + "\t" + line.category(),
                    key -> new Count(new int[5], new TreeMap<>()));
            if (!line.scored()) {
                count.numbers()[4]++;
                continue;
            }
            boolean product = !line.expected().equals("none");
            count.numbers()[product ? 0 : 2]++;
            if (line.result().correct) {
                count.numbers()[product ? 1 : 3]++;
            } else if (product) {
                count.said().merge(line.result() == Result.WRONG ? "WRONG " + line.observed()
                        : line.said().isEmpty() ? "nothing said" : line.said(), 1, Integer::sum);
            }
        }
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%-11s %-62s %14s %13s %8s  %s%n", "Suite",
                "Category", "Products found", "None, rightly", "Unscored", "Of the products not found, the run said"));
        counts.forEach((key, count) -> {
            String[] names = key.split("\t");
            int[] n = count.numbers();
            text.append(String.format(Locale.ROOT, "%-11s %-62s %14s %13s %8s  %s%n", names[0], names[1],
                    n[0] == 0 ? "" : n[1] + " / " + n[0], n[2] == 0 ? "" : n[3] + " / " + n[2],
                    n[4] == 0 ? "" : String.valueOf(n[4]),
                    count.said().entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey())
                            .collect(Collectors.joining("; "))));
        });
        long scored = lines.stream().filter(Line::scored).count();
        long correct = lines.stream().filter(line -> line.scored() && line.result().correct).count();
        text.append(String.format(Locale.ROOT, "%nScored cases: %d, correct: %d, not: %d. Seconds: %.0f.%n", scored,
                correct, scored - correct, timings.stream().mapToDouble(Timing::seconds).sum()));
        return text.toString();
    }

    // ---- The baseline: the result of every case in a version, kept in the repository. ----

    /** A baseline's line. */
    record Kept(String result, boolean scored, String said) {
    }

    /** This scorecard as a baseline file: case, result, scored, what the run said. */
    String baseline() {
        StringBuilder text = new StringBuilder("Case\tResult\tScored\tSaid\n");
        for (Line line : sorted()) {
            text.append(String.join("\t", line.key(), line.result().name(), line.scored() ? "yes" : "no", line.said()))
                    .append('\n');
        }
        return text.toString();
    }

    static Map<String, Kept> readBaseline(Path file) throws IOException {
        return readBaselineText(Files.readString(file, StandardCharsets.UTF_8));
    }

    static Map<String, Kept> readBaselineText(String text) {
        Map<String, Kept> kept = new TreeMap<>();
        for (Map<String, String> row : Observed.rows(text)) {
            kept.put(row.get("Case"), new Kept(row.get("Result"), "yes".equals(row.get("Scored")), row.get("Said")));
        }
        return kept;
    }

    /**
     * How this scorecard differs from a baseline.
     *
     * @param worse   scored cases right in the baseline and wrong now, or wrong in another way
     * @param better  scored cases wrong in the baseline and right now
     * @param changed cases whose result is as wrong as before, or unscored, but that differ: what the run said, a
     *                case added or gone
     */
    record Difference(List<String> worse, List<String> better, List<String> changed) {
        boolean none() {
            return worse.isEmpty() && better.isEmpty() && changed.isEmpty();
        }

        String text() {
            StringBuilder text = new StringBuilder();
            section(text, "Worse than the baseline", worse);
            section(text, "Better than the baseline", better);
            section(text, "Changed", changed);
            return text.isEmpty() ? "Same as the baseline.\n" : text.toString();
        }

        private static void section(StringBuilder text, String title, List<String> lines) {
            if (!lines.isEmpty()) {
                text.append(title).append(" (").append(lines.size()).append("):\n");
                lines.forEach(line -> text.append("  ").append(line).append('\n'));
            }
        }
    }

    Difference compare(Map<String, Kept> baseline) {
        List<String> worse = new ArrayList<>();
        List<String> better = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Line line : sorted()) {
            seen.add(line.key());
            Kept kept = baseline.get(line.key());
            String now = line.result().name();
            String detail = line.key() + ": " + line.sample() + " " + line.assay() + ", expected " + line.expected()
                    + ", reported " + line.observed() + (line.said().isEmpty() ? "" : " (" + line.said() + ")");
            if (kept == null) {
                (line.scored() && !line.result().correct ? worse : changed).add(detail + " [new case: " + now + "]");
                continue;
            }
            boolean wasCorrect = Result.valueOf(kept.result()).correct;
            String from = " [" + kept.result() + (kept.said().isEmpty() ? "" : " (" + kept.said() + ")") + " -> " + now + "]";
            if (!line.scored() || !kept.scored()) {
                if (!kept.result().equals(now) || kept.scored() != line.scored()) {
                    changed.add(detail + from + (line.scored() == kept.scored() ? " (not scored)" : " (scoring changed)"));
                }
            } else if (wasCorrect && !line.result().correct) {
                worse.add(detail + from);
            } else if (!wasCorrect && line.result().correct) {
                better.add(detail + from);
            } else if (!kept.result().equals(now)) {
                worse.add(detail + from);
            } else if (!kept.said().equals(line.said())) {
                changed.add(detail + from);
            }
        }
        // Of the suites that were run only: a baseline holds every suite.
        Set<String> suites = lines.stream().map(Line::suite).collect(Collectors.toSet());
        baseline.forEach((key, kept) -> {
            if (seen.contains(key) || !suites.contains(key.substring(0, key.indexOf('/')))) {
                return;
            }
            // A product the baseline had where no case expects one, and that is gone.
            (key.contains("/unlisted:") ? better : changed).add(key + " [" + kept.result() + " in the baseline, "
                    + (key.contains("/unlisted:") ? "no longer reported]" : "not run]"));
        });
        return new Difference(worse, better, changed);
    }
}
