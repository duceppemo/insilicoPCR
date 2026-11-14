package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.report.HtmlReport;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.util.InputCheck;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** What the HTML report lists about a run; shared by the command-line and GUI pipelines. */
final class RunDetails {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private RunDetails() {
    }

    /**
     * @param withFastq whether the run has a FASTQ sample, whose settings are then listed too
     * @return label and value pairs in display order
     */
    static Map<String, String> of(String version, PipelineConfig config, boolean withFastq) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Date", DATE_FORMAT.format(LocalDateTime.now()));
        details.put("insilicoPCR version", version);
        details.put("Input", String.valueOf(config.input()));
        details.put("Primer file", String.valueOf(config.primers()));
        details.put("Output folder", String.valueOf(config.output()));
        details.putAll(HtmlReport.settings(config.ampliconOptions()));
        if (withFastq) {
            double readMinFraction = config.readMinFraction();
            ReadExtensionCaller.Mode readExtension = config.readExtension();
            details.put("Read-level calls (FASTQ)", "at least " + ReadLevelCaller.MIN_SUPPORTING_READS
                    + " reads or read pairs and " + Math.round(100 * readMinFraction) + "% of informative reads");
            details.put("Read extension (FASTQ)", readExtension == ReadExtensionCaller.Mode.OFF ? "off"
                    : readExtension + ": for " + readExtension.description() + ", up to "
                    + ReadExtensionCaller.MAX_SEEDS_PER_PRIMER + " reads per primer are extended through the baited"
                    + " reads (k=" + ReadExtensionCaller.K + "); a product needs at least "
                    + ReadExtensionCaller.MIN_CLOSING_READS_PER_SIDE + " extended reads seeded from each primer, and"
                    + " read pairs holding every window of it");
        }
        return details;
    }

    /** What QAlog.txt says about the input that was left out; empty when nothing was. */
    static List<String> inputIssueLines(List<InputCheck.Issue> issues) {
        if (issues.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> bySample = new LinkedHashMap<>();
        issues.forEach(issue -> bySample.computeIfAbsent(issue.sample(), ignored -> new ArrayList<>())
                .add(issue.describe()));
        List<String> lines = new ArrayList<>();
        lines.add("Input not analysed: a file of these samples is faulty, and nothing was run on them.");
        bySample.forEach((sample, problems) -> {
            lines.add(QaLogInputs.NOT_ANALYSED_PREFIX + sample);
            problems.forEach(problem -> lines.add("  " + problem));
        });
        lines.add("");
        return lines;
    }

    /** Names of the samples given as reads. */
    static Set<String> fastqSampleNames(Map<String, Sample> samples) {
        Set<String> names = new TreeSet<>();
        samples.forEach((name, sample) -> {
            if (sample.isFastq()) {
                names.add(name);
            }
        });
        return names;
    }
}
