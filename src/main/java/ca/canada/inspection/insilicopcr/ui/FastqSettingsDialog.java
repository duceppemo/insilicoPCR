package ca.canada.inspection.insilicopcr.ui;

import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pipeline.TadpoleOptions;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * Dialog for the FASTQ-mode settings: the Tadpole mini-assembly, the read-level
 * call threshold and the read extension. Mirrors the {@code --tadpole-*},
 * {@code --read-min-fraction} and {@code --read-extension} command-line options.
 */
public final class FastqSettingsDialog {

    /** The FASTQ-mode settings edited by this dialog. */
    public record FastqSettings(TadpoleOptions tadpole, double readMinFraction,
                                ReadExtensionCaller.Mode readExtension) {
        public static final FastqSettings DEFAULTS = new FastqSettings(TadpoleOptions.DEFAULTS,
                ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION, ReadExtensionCaller.Mode.DEFAULT);

        /** Settings with the default read extension. */
        public FastqSettings(TadpoleOptions tadpole, double readMinFraction) {
            this(tadpole, readMinFraction, ReadExtensionCaller.Mode.DEFAULT);
        }

        public boolean isDefault() {
            return equals(DEFAULTS);
        }
    }

    private FastqSettingsDialog() {
    }

    /** @return the edited settings, or empty when the dialog was cancelled */
    public static Optional<FastqSettings> show(Window owner, FastqSettings current) {
        var dialog = new SettingsDialog();
        dialog.section("Tadpole mini-assembly");
        TextField k = dialog.field("k-mer length", String.valueOf(current.tadpole().k()),
                "Tadpole k (--tadpole-k). Default " + TadpoleOptions.DEFAULT_K + ".");
        TextField minSeed = dialog.field("Min k-mer count to seed", String.valueOf(current.tadpole().minCountSeed()),
                "Minimum k-mer count to start a contig (--tadpole-min-seed, Tadpole mincountseed). Default "
                        + TadpoleOptions.DEFAULT_MIN_COUNT_SEED + ".");
        TextField minCov = dialog.field("Min contig coverage", String.valueOf(current.tadpole().minCoverage()),
                "Minimum average coverage of a kept contig (--tadpole-min-cov, Tadpole mincov). Default "
                        + TadpoleOptions.DEFAULT_MIN_COVERAGE + ".");
        TextField minContig = dialog.field("Min contig length (bp)", String.valueOf(current.tadpole().minContig()),
                "Shortest contig kept (--tadpole-min-contig, Tadpole mincontig). Keep it below the shortest "
                        + "amplicon you expect. Default " + TadpoleOptions.DEFAULT_MIN_CONTIG + ".");
        dialog.section("Read-level calls");
        TextField readFraction = dialog.field("Min share of informative reads (0-1)",
                String.valueOf(current.readMinFraction()),
                "A product the mini-assembly missed is reported from reads when at least "
                        + ReadLevelCaller.MIN_SUPPORTING_READS + " reads (the two reads of a pair count once), and at "
                        + "least this share of the reads that "
                        + "carry one of its primers at the same locus and are long enough, hold a complete amplicon "
                        + "(--read-min-fraction). Default " + ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION + ".");
        dialog.section("Read extension");
        ChoiceBox<ReadExtensionCaller.Mode> readExtension = dialog.choice("Extend reads for",
                List.of(ReadExtensionCaller.Mode.values()), current.readExtension(),
                "Finds products longer than a read or merged pair (--read-extension). When reads carry both primers "
                        + "of an assay, up to " + ReadExtensionCaller.MAX_SEEDS_PER_PRIMER + " reads per primer are "
                        + "extended through the baited reads, and a product that at least "
                        + ReadExtensionCaller.MIN_CLOSING_READS_PER_SIDE + " reads extended from each primer hold, and "
                        + "that the sample's read pairs confirm, is reported.\nunreported: " + ReadExtensionCaller.Mode.UNREPORTED.description()
                        + ".\ntarget: " + ReadExtensionCaller.Mode.TARGET.description()
                        + " (size= in the primer file).\noff: no read is extended.\nAdds from seconds to a few "
                        + "minutes per sample that has such an assay. Default "
                        + ReadExtensionCaller.Mode.DEFAULT + ".");

        return dialog.show(owner, "FASTQ settings", "Settings used only for FASTQ (read) input",
                () -> parse(k.getText(), minSeed.getText(), minCov.getText(), minContig.getText(),
                        readFraction.getText(), readExtension.getValue()),
                () -> {
                    FastqSettings defaults = FastqSettings.DEFAULTS;
                    k.setText(String.valueOf(defaults.tadpole().k()));
                    minSeed.setText(String.valueOf(defaults.tadpole().minCountSeed()));
                    minCov.setText(String.valueOf(defaults.tadpole().minCoverage()));
                    minContig.setText(String.valueOf(defaults.tadpole().minContig()));
                    readFraction.setText(String.valueOf(defaults.readMinFraction()));
                    readExtension.setValue(defaults.readExtension());
                });
    }

    /** Label for the button that opens the dialog. */
    public static String buttonLabel(FastqSettings settings) {
        return "FASTQ settings…" + (settings.isDefault() ? "" : SettingsDialog.CHANGED_MARK);
    }

    /** Tooltip summarizing the current settings. */
    public static String summary(FastqSettings settings) {
        return (settings.isDefault() ? "Defaults" : "Changed from defaults (*)")
                + "\nTadpole: " + String.join(" ", settings.tadpole().arguments())
                + "\nRead-level calls: at least " + ReadLevelCaller.MIN_SUPPORTING_READS + " reads or read pairs and "
                + Math.round(100 * settings.readMinFraction()) + "% of informative reads"
                + "\nRead extension: " + settings.readExtension();
    }

    /**
     * Parses the dialog's field texts.
     *
     * @throws IllegalArgumentException with a user-facing message when a value is invalid
     */
    static FastqSettings parse(String k, String minSeed, String minCov, String minContig, String readFraction) {
        return parse(k, minSeed, minCov, minContig, readFraction, ReadExtensionCaller.Mode.DEFAULT);
    }

    /** As {@link #parse(String, String, String, String, String)}, with the read extension chosen. */
    static FastqSettings parse(String k, String minSeed, String minCov, String minContig, String readFraction,
                               ReadExtensionCaller.Mode readExtension) {
        TadpoleOptions tadpole = new TadpoleOptions(
                SettingsDialog.parseInt(k, "k-mer length", 1),
                SettingsDialog.parseInt(minSeed, "Min k-mer count to seed", 1),
                SettingsDialog.parseInt(minCov, "Min contig coverage", 0),
                SettingsDialog.parseInt(minContig, "Min contig length", 1));
        double fraction;
        try {
            fraction = Double.parseDouble(readFraction.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Min share of informative reads must be a number between 0 and 1.");
        }
        if (!(fraction >= 0.0 && fraction <= 1.0)) {
            throw new IllegalArgumentException("Min share of informative reads must be between 0 and 1.");
        }
        return new FastqSettings(tadpole, fraction, readExtension);
    }
}
