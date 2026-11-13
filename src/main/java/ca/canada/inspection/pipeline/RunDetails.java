package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.HtmlReport;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** What the HTML report lists about a run; shared by the command-line and GUI pipelines. */
final class RunDetails {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private RunDetails() {
    }

    /**
     * @param readMinFraction read-level call threshold, or {@code null} when the run has no FASTQ sample
     * @param readExtension   which assays reads are extended for
     * @return label and value pairs in display order
     */
    static Map<String, String> of(String version, Path input, Path primers, Path output, AmpliconOptions options,
                                  Double readMinFraction, ReadExtensionCaller.Mode readExtension) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Date", DATE_FORMAT.format(LocalDateTime.now()));
        details.put("insilicoPCR version", version);
        details.put("Input", String.valueOf(input));
        details.put("Primer file", String.valueOf(primers));
        details.put("Output folder", String.valueOf(output));
        details.putAll(HtmlReport.settings(options));
        if (readMinFraction != null) {
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
