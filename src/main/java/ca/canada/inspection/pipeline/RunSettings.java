package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.PairSupport;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.report.RunDetails;
import ca.canada.inspection.report.RunDetails.Setting;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The settings of a run, described once: the HTML report's "Run details", {@code QAlog.txt}
 * and the run's log all list these, so none of them leaves one out or describes it otherwise.
 */
public final class RunSettings {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private RunSettings() {
    }

    /**
     * The settings that decide the run's results, each labelled with its command-line option.
     *
     * @param withFastq whether the run has a FASTQ sample, whose settings are then listed too
     */
    static List<Setting> of(PipelineConfig config, boolean withFastq) {
        AmpliconOptions options = config.ampliconOptions();
        List<Setting> settings = new ArrayList<>();
        settings.add(new Setting("Primer mismatches (-m)", String.valueOf(options.maxTotalMismatches())));
        settings.add(new Setting("Probe mismatches (--probe-mismatches)", String.valueOf(options.maxProbeMismatches())));
        settings.add(new Setting("3' window / max penalty (--3p-window / --3p-penalty)",
                options.threePrimeWindow() + " / " + options.maxThreePrimePenalty()));
        settings.add(new Setting("5' truncation allowed (--5p-truncation)",
                String.valueOf(options.maxFivePrimeTruncation())));
        settings.add(new Setting("Product length (--min-length to --max-length)", options.minAmpliconLength() + " to "
                + (options.maxAmpliconLength() > 0 ? options.maxAmpliconLength() + " bp" : "no limit")));
        settings.add(new Setting("BLAST e-value (-e)", number(config.evalue())));
        settings.add(new Setting("Threads (-t)", String.valueOf(config.threads())));
        settings.add(new Setting("Input check (--input-check)", config.inputCheck().option()));
        if (withFastq) {
            settings.add(new Setting("Tadpole assembly (FASTQ, --tadpole-...)",
                    String.join(" ", config.tadpole().arguments())));
            settings.add(new Setting("Read-level calls (FASTQ, --read-min-fraction)",
                    readLevelCalls(config.readMinFraction())));
            settings.add(new Setting("Read extension (FASTQ, --read-extension)", readExtension(config.readExtension())));
            settings.add(new Setting("BBTools memory (FASTQ, --bbtools-memory)", bbtoolsMemory(config.bbtoolsMemoryGiB())));
        }
        return settings;
    }

    /** The HTML report's "Run details": the run's paths, then its settings. */
    static RunDetails details(String version, PipelineConfig config, boolean withFastq) {
        List<Setting> settings = new ArrayList<>();
        settings.add(new Setting("Input", String.valueOf(config.input())));
        settings.add(new Setting("Primer file", String.valueOf(config.primers())));
        settings.add(new Setting("Output folder", String.valueOf(config.output())));
        settings.addAll(of(config, withFastq));
        return new RunDetails(DATE_FORMAT.format(LocalDateTime.now()), version, settings);
    }

    /** The settings as lines of a log: a heading, then one indented line each. */
    static List<String> lines(PipelineConfig config, boolean withFastq) {
        List<String> lines = new ArrayList<>();
        lines.add("Settings:");
        of(config, withFastq).forEach(setting -> lines.add("  " + setting.label() + ": " + setting.value()));
        return lines;
    }

    /** The BBTools memory asked for: so many GB, or worked out from the computer. */
    public static String bbtoolsMemory(int gib) {
        return gib > 0 ? gib + " GB for the programs running at once"
                : "automatic: three quarters of the memory insilicoPCR leaves free, at least half of its heap";
    }

    /** What a read-level call needs, with this share of informative reads. */
    public static String readLevelCalls(double readMinFraction) {
        return "at least " + ReadLevelCaller.MIN_SUPPORTING_READS + " reads or read pairs and "
                + Math.round(100 * readMinFraction) + "% of informative reads";
    }

    /** What the read extension does in {@code mode}. */
    public static String readExtension(ReadExtensionCaller.Mode mode) {
        if (mode == ReadExtensionCaller.Mode.OFF) {
            return "off";
        }
        return mode + ": for " + mode.description() + ", when reads carry both primers, up to "
                + ReadExtensionCaller.MAX_SEEDS_PER_PRIMER + " reads per primer are extended through the baited"
                + " reads (Tadpole mode=extend k=" + ReadExtensionCaller.K + " "
                + String.join(" ", BbtoolsCommands.EXTEND_OPTIONS) + "); a product needs at least "
                + ReadExtensionCaller.MIN_CLOSING_READS_PER_SIDE + " extended reads seeded from each primer, and"
                + " read pairs holding each of its windows (the largest window of " + PairSupport.MIN_WINDOW + ", "
                + (PairSupport.MIN_WINDOW + PairSupport.WINDOW_STEP) + ", ... bases whose median is held by "
                + PairSupport.MIN_MEDIAN_FRAGMENTS + " pairs; none held by fewer than "
                + PairSupport.MIN_WINDOW_FRAGMENTS + " or " + Math.round(100 * PairSupport.MIN_WINDOW_SHARE)
                + "% of the median), and no repeat that long with another copy in the sample";
    }

    /** A number as a person writes it: 100000, 0.05; very large or small ones as 1.0E-30. */
    static String number(double value) {
        double size = Math.abs(value);
        if (value == 0 || (size >= 1e-6 && size < 1e9)) {
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
