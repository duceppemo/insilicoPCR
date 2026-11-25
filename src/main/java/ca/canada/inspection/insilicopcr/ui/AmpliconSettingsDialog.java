package ca.canada.inspection.insilicopcr.ui;

import ca.canada.inspection.util.Numbers;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.TmGate;
import javafx.scene.control.TextField;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Dialog for the amplicon-calling settings not on the main window: the Tm margin and the reaction the Tm is worked out
 * for, the 3'-end check and the amplicon size limits. Mirrors the {@code --tm-margin}, {@code --na}, {@code --mg},
 * {@code --dntp}, {@code --oligo-conc}, {@code --3p-window}, {@code --3p-penalty}, {@code --min-length} and
 * {@code --max-length} command-line options. The annealing temperature stays on the main window.
 */
public final class AmpliconSettingsDialog {

    public record AmpliconSettings(double tmMargin, double monovalentMm, double divalentMm, double dntpMm,
                                   double oligoNm, int threePrimeWindow, int threePrimePenalty, int minLength,
                                   int maxLength) {
        public static final AmpliconSettings DEFAULTS = new AmpliconSettings(TmGate.DEFAULT_MARGIN,
                TmGate.DEFAULT_MONOVALENT_MM, TmGate.DEFAULT_DIVALENT_MM, TmGate.DEFAULT_DNTP_MM,
                TmGate.DEFAULT_OLIGO_NM, AmpliconOptions.DEFAULT_THREE_PRIME_WINDOW,
                AmpliconOptions.DEFAULT_THREE_PRIME_PENALTY, AmpliconOptions.DEFAULT_MIN_AMPLICON_LENGTH,
                AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH);

        public boolean isDefault() {
            return equals(DEFAULTS);
        }

        /** Full amplicon options with the main window's annealing temperature. */
        public AmpliconOptions toOptions(double annealTemp) {
            return AmpliconOptions.builder()
                    .threePrimeWindow(threePrimeWindow).threePrimePenalty(threePrimePenalty)
                    .minLength(minLength).maxLength(maxLength)
                    .tmGate(new TmGate(annealTemp, tmMargin, monovalentMm, divalentMm, dntpMm, oligoNm))
                    .build();
        }
    }

    private AmpliconSettingsDialog() {
    }

    /** @return the edited settings, or empty when the dialog was cancelled */
    public static Optional<AmpliconSettings> show(Window owner, AmpliconSettings current) {
        var dialog = new SettingsDialog();
        dialog.section("Primer and probe sites");
        TextField margin = dialog.field("Tm margin (°C)", Numbers.plain(current.tmMargin()),
                "A primer or probe site is accepted when its Tm on the template reaches the annealing temperature "
                        + "(main window) less this margin (--tm-margin). Raise it, or lower the annealing temperature, "
                        + "to accept weaker sites. Default " + Numbers.plain(TmGate.DEFAULT_MARGIN) + ".");
        TextField na = dialog.field("Na+ / K+ (mM)", Numbers.plain(current.monovalentMm()),
                "Monovalent cations of the reaction, for the Tm (--na). Default "
                        + Numbers.plain(TmGate.DEFAULT_MONOVALENT_MM) + ".");
        TextField mg = dialog.field("Mg2+ (mM)", Numbers.plain(current.divalentMm()),
                "Mg2+ of the reaction, for the Tm (--mg). Default " + Numbers.plain(TmGate.DEFAULT_DIVALENT_MM) + ".");
        TextField dntp = dialog.field("dNTP (mM)", Numbers.plain(current.dntpMm()),
                "dNTP of the reaction, for the Tm (--dntp). Default " + Numbers.plain(TmGate.DEFAULT_DNTP_MM) + ".");
        TextField oligo = dialog.field("Each oligo (nM)", Numbers.plain(current.oligoNm()),
                "Concentration of each primer and probe, for the Tm (--oligo-conc). Default "
                        + Numbers.plain(TmGate.DEFAULT_OLIGO_NM) + ".");
        dialog.section("Primer 3' end");
        TextField window = dialog.field("3' window (bases)", String.valueOf(current.threePrimeWindow()),
                "Bases at the primer 3' end checked with the weighted 3' penalty (--3p-window). Default "
                        + AmpliconOptions.DEFAULT_THREE_PRIME_WINDOW + ".");
        TextField penalty = dialog.field("Max 3' penalty", String.valueOf(current.threePrimePenalty()),
                "Maximum weighted penalty inside the 3' window (--3p-penalty). A mismatch scores 3 on the terminal "
                        + "base, 2 at -1/-2 and 1 beyond; +1 if it breaks a G/C pair near the terminus; an indel scores "
                        + "as a mismatch at its place, plus 1. 0 requires a perfect 3' end; 3 tolerates one A/T terminal mismatch. Default "
                        + AmpliconOptions.DEFAULT_THREE_PRIME_PENALTY + ".");
        dialog.section("Product size");
        TextField minLength = dialog.field("Min amplicon (bp)", String.valueOf(current.minLength()),
                "Products shorter than this are not reported (--min-length). Default "
                        + AmpliconOptions.DEFAULT_MIN_AMPLICON_LENGTH + ".");
        TextField maxLength = dialog.field("Max amplicon (bp, 0 = no limit)", String.valueOf(current.maxLength()),
                "Primer pairs farther apart than this are not reported (--max-length). Use 0 to disable the "
                        + "limit, e.g. for long-range PCR. Default " + AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH + ".");

        return dialog.show(owner, "Amplicon settings", "Amplicon-calling settings (the annealing temperature is on"
                        + " the main window)",
                () -> parse(margin.getText(), na.getText(), mg.getText(), dntp.getText(), oligo.getText(),
                        window.getText(), penalty.getText(), minLength.getText(), maxLength.getText()),
                () -> {
                    AmpliconSettings defaults = AmpliconSettings.DEFAULTS;
                    margin.setText(Numbers.plain(defaults.tmMargin()));
                    na.setText(Numbers.plain(defaults.monovalentMm()));
                    mg.setText(Numbers.plain(defaults.divalentMm()));
                    dntp.setText(Numbers.plain(defaults.dntpMm()));
                    oligo.setText(Numbers.plain(defaults.oligoNm()));
                    window.setText(String.valueOf(defaults.threePrimeWindow()));
                    penalty.setText(String.valueOf(defaults.threePrimePenalty()));
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
                + "\nTm margin " + Numbers.plain(settings.tmMargin()) + " °C; " + Numbers.plain(settings.monovalentMm())
                + " mM Na+, " + Numbers.plain(settings.divalentMm()) + " mM Mg2+, " + Numbers.plain(settings.dntpMm())
                + " mM dNTP, " + Numbers.plain(settings.oligoNm()) + " nM each oligo"
                + "\n3' window " + settings.threePrimeWindow() + ", max 3' penalty " + settings.threePrimePenalty()
                + "\nAmplicon size: " + settings.minLength() + " to "
                + (settings.maxLength() == 0 ? "unlimited" : settings.maxLength() + " bp");
    }

    /**
     * Parses the dialog's field texts.
     *
     * @throws IllegalArgumentException with a user-facing message when a value is invalid
     */
    static AmpliconSettings parse(String margin, String na, String mg, String dntp, String oligo, String window,
                                  String penalty, String minLength, String maxLength) {
        int min = SettingsDialog.parseInt(minLength, "Min amplicon", 0);
        int max = SettingsDialog.parseInt(maxLength, "Max amplicon", 0);
        if (!AmpliconOptions.lengthsInOrder(min, max)) {
            throw new IllegalArgumentException("Min amplicon (" + min + " bp) is larger than Max amplicon ("
                    + max + " bp).");
        }
        double oligoNm = SettingsDialog.parseNumber(oligo, "Each oligo");
        if (!(oligoNm > 0)) {
            throw new IllegalArgumentException("Each oligo must be greater than 0 nM.");
        }
        double monovalent = SettingsDialog.parseNumber(na, "Na+ / K+");
        double divalent = SettingsDialog.parseNumber(mg, "Mg2+");
        double dntpMm = SettingsDialog.parseNumber(dntp, "dNTP");
        if (TmGate.saltError(monovalent, divalent, dntpMm).isPresent()) {
            throw new IllegalArgumentException("No cations left for a Tm: give Na+ / K+ above 0, or Mg2+ above dNTP"
                    + " (the dNTP bind their own amount of Mg2+).");
        }
        return new AmpliconSettings(SettingsDialog.parseNumber(margin, "Tm margin"),
                monovalent, divalent, dntpMm, oligoNm,
                SettingsDialog.parseInt(window, "3' window", 0),
                SettingsDialog.parseInt(penalty, "Max 3' penalty", 0),
                min, max);
    }
}
