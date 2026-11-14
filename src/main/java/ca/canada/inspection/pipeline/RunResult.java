package ca.canada.inspection.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a finished run wrote, so the application shows this run's reports instead of
 * looking for the newest file in the output folder.
 *
 * @param report  the consolidated {@code report.tsv} (or {@code report(<n>).tsv})
 * @param html    the HTML report next to it
 * @param samples the samples the run analysed, in run order: one gel lane each, with or
 *                without a band (samples left out by the input check are not among them)
 */
public record RunResult(Path report, Path html, List<String> samples) {
    public RunResult {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(html, "html");
        samples = List.copyOf(samples);
    }
}
