package ca.canada.inspection.insilicopcr.ui;

import ca.canada.inspection.pcr.AmpliconOptions;
import javafx.scene.control.TextField;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Dialog for the amplicon-calling settings not on the main window: probe
 * mismatches, the 3'-end check, the 5'-truncation allowance and the amplicon size
 * limits. Mirrors the {@code --probe-mismatches}, {@code --3p-window},
 * {@code --3p-penalty}, {@code --5p-truncation}, {@code --min-length} and
 * {@code --max-length} command-line options. Primer mismatches stay on the main
 * window.
 */
public final class AmpliconSettingsDialog {

    /**
     * @param probeMismatches allowed probe mismatches + indels, or {@code null} for the
     *                        same as the primer mismatches (the command-line default)
     */
    public record AmpliconSettings(Integer probeMismatches, int threePrimeWindow, int threePrimePenalty,
                                   int fivePrimeTruncation, int minLength, int maxLength) {
        public static final AmpliconSettings DEFAULTS = new AmpliconSettings(null,
                AmpliconOptions.DEFAULT_THREE_PRIME_WINDOW, AmpliconOptions.DEFAULT_THREE_PRIME_PENALTY,
                AmpliconOptions.DEFAULT_FIVE_PRIME_TRUNCATION, AmpliconOptions.DEFAULT_MIN_AMPLICON_LENGTH,
                AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH);

        public boolean isDefault() {
            return equals(DEFAULTS);
        }

        /** Full amplicon options with the main window's primer mismatches. */
        public AmpliconOptions toOptions(int mismatches) {
            return new AmpliconOptions(mismatches, probeMismatches == null ? mismatches : probeMismatches,
                    threePrimeWindow, threePrimePenalty, fivePrimeTruncation, minLength, maxLength);
        }
    }

    private AmpliconSettingsDialog() {
    }

    /** @return the edited settings, or empty when the dialog was cancelled */
    public static Optional<AmpliconSettings> show(Window owner, AmpliconSettings current) {
        var dialog = new SettingsDialog();
        dialog.section("Probe");
        TextField probe = dialog.field("Probe mismatches (blank = as primers)",
                current.probeMismatches() == null ? "" : String.valueOf(current.probeMismatches()),
                "Allowed mismatches + indels per hydrolysis probe (--probe-mismatches). Probes bind hotter than "
                        + "primers and tolerate roughly one more mismatch. Leave blank to use the primer "
                        + "Mismatches value from the main window.");
        dialog.section("Primer ends");
        TextField window = dialog.field("3' window (bases)", String.valueOf(current.threePrimeWindow()),
                "Bases at the primer 3' end checked with the weighted 3' penalty (--3p-window). Default "
                        + AmpliconOptions.DEFAULT_THREE_PRIME_WINDOW + ".");
        TextField penalty = dialog.field("Max 3' penalty", String.valueOf(current.threePrimePenalty()),
                "Maximum weighted penalty inside the 3' window (--3p-penalty). A mismatch scores 3 on the terminal "
                        + "base, 2 at -1/-2 and 1 beyond; +1 if it breaks a G/C pair near the terminus; +1 for an "
                        + "indel. 0 requires a perfect 3' end; 3 tolerates one A/T terminal mismatch. Default "
                        + AmpliconOptions.DEFAULT_THREE_PRIME_PENALTY + ".");
        TextField truncation = dialog.field("Max 5' unaligned bases", String.valueOf(current.fivePrimeTruncation()),
                "Primer bases allowed to be unaligned at the harmless 5' end (--5p-truncation). One 5'-proximal "
                        + "mismatch makes BLAST trim the whole tail. Default "
                        + AmpliconOptions.DEFAULT_FIVE_PRIME_TRUNCATION + ".");
        dialog.section("Product size");
        TextField minLength = dialog.field("Min amplicon (bp)", String.valueOf(current.minLength()),
                "Products shorter than this are not reported (--min-length). Default "
                        + AmpliconOptions.DEFAULT_MIN_AMPLICON_LENGTH + ".");
        TextField maxLength = dialog.field("Max amplicon (bp, 0 = no limit)", String.valueOf(current.maxLength()),
                "Primer pairs farther apart than this are not reported (--max-length). Use 0 to disable the "
                        + "limit, e.g. for long-range PCR. Default " + AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH + ".");

        return dialog.show(owner, "Amplicon settings", "Amplicon-calling settings (primer mismatches are on the"
                        + " main window)",
                () -> parse(probe.getText(), window.getText(), penalty.getText(), truncation.getText(),
                        minLength.getText(), maxLength.getText()),
                () -> {
                    AmpliconSettings defaults = AmpliconSettings.DEFAULTS;
                    probe.setText("");
                    window.setText(String.valueOf(defaults.threePrimeWindow()));
                    penalty.setText(String.valueOf(defaults.threePrimePenalty()));
                    truncation.setText(String.valueOf(defaults.fivePrimeTruncation()));
                    minLength.setText(String.valueOf(defaults.minLength()));
                    maxLength.setText(String.valueOf(defaults.maxLength()));
                });
    }

    /** Label for the button that opens the dialog. */
    public static String buttonLabel(AmpliconSettings settings) {
        return "Amplicon settings…" + (settings.isDefault() ? "" : SettingsDialog.CHANGED_MARK);
    }

    /** Tooltip summarizing the current settings. */
    public static String summary(AmpliconSettings settings) {
        return (settings.isDefault() ? "Defaults" : "Changed from defaults (*)")
                + "\nProbe mismatches: " + (settings.probeMismatches() == null ? "as primers" : settings.probeMismatches())
                + "\n3' window " + settings.threePrimeWindow() + ", max 3' penalty " + settings.threePrimePenalty()
                + "\nMax 5' unaligned bases: " + settings.fivePrimeTruncation()
                + "\nAmplicon size: " + settings.minLength() + " to "
                + (settings.maxLength() == 0 ? "unlimited" : settings.maxLength() + " bp");
    }

    /**
     * Parses the dialog's field texts.
     *
     * @throws IllegalArgumentException with a user-facing message when a value is invalid
     */
    static AmpliconSettings parse(String probe, String window, String penalty, String truncation,
                                  String minLength, String maxLength) {
        Integer probeMismatches = probe.isBlank() ? null : SettingsDialog.parseInt(probe, "Probe mismatches", 0);
        int min = SettingsDialog.parseInt(minLength, "Min amplicon", 0);
        int max = SettingsDialog.parseInt(maxLength, "Max amplicon", 0);
        if (max > 0 && min > max) {
            throw new IllegalArgumentException("Min amplicon (" + min + " bp) is larger than Max amplicon ("
                    + max + " bp).");
        }
        return new AmpliconSettings(probeMismatches,
                SettingsDialog.parseInt(window, "3' window", 0),
                SettingsDialog.parseInt(penalty, "Max 3' penalty", 0),
                SettingsDialog.parseInt(truncation, "Max 5' unaligned bases", 0),
                min, max);
    }
}
