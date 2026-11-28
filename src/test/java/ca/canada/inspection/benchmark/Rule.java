package ca.canada.inspection.benchmark;

import ca.canada.inspection.thermo.Thal;

/**
 * The rule a primer or probe site is accepted by, as the documentation states it (Amplicon Calling), applied to a
 * site the benchmark planted itself: the oligo's Tm on the template strand under it must reach the annealing
 * temperature less the margin, and a primer's last bases must pair. It is worked out here from the planted bases
 * alone, by Primer3's thermodynamic alignment ({@link Thal}, checked against {@code ntthal} by its own tests), so
 * that the answer does not depend on how the program under test finds its sites: what the benchmark measures is
 * whether the search finds the sites the rule accepts, and only those.
 *
 * <p>How many template bases lie "under" an oligo is a convention, and the Tm depends on it: the bases beside a site
 * stack on the duplex's ends, and with a base more or less the alignment of a site with an indel can change (on one
 * 1-base deletion, 54.1 °C, and 51.2 °C with one template base more). The rule is therefore applied to every window from
 * the oligo's footprint to the footprint with the bases beside it ({@link #judge}), and a site counts as accepted
 * only when every window is above the minimum by {@link #guard}, as rejected only when every window is below it by
 * as much. Any other site is {@link Verdict#BORDERLINE}: it would pass under one convention and fail under another
 * without either being wrong. Borderline sites are run and listed, not scored.</p>
 *
 * @param minimumTm the lowest Tm of an accepted site, °C ({@code --anneal-temp} less {@code --tm-margin})
 * @param guard     how far from {@code minimumTm} every window's Tm must be for the site to be scored, °C
 * @param window    the 3' bases of a primer that must pair ({@code --3p-window}, with {@code --3p-penalty 0})
 */
record Rule(double minimumTm, double guard, int window) {

    /** The program's defaults: 60 °C less 5, a 3' window of 5 without a mismatch. */
    static final Rule DEFAULT = new Rule(55, 0.5, 5);

    /** The reaction of the program's defaults: 50 mM Na+, 3 mM Mg2+, 0.8 mM dNTP, 250 nM oligo. */
    private static final Thal.Args REACTION = new Thal.Args(Thal.Type.ANY, Thal.MAX_LOOP, 50, 3, 0.8, 250,
            Thal.TEMP_KELVIN);

    enum Verdict {
        ACCEPT, REJECT, BORDERLINE
    }

    /**
     * What the rule says of one site.
     *
     * @param tm     the oligo's Tm on its footprint, °C
     * @param lowest the lowest Tm over the windows, °C
     * @param highest the highest
     * @param why    the rule that decides, for the scorecard
     */
    record Site(Verdict verdict, double tm, double lowest, double highest, String why) {

        /** For the scorecard: {@code site Tm 57.2 (56.8 to 58.1); accept: Tm}. */
        String text() {
            return String.format(java.util.Locale.ROOT, "site Tm %.1f (%.1f to %.1f); %s: %s", tm, lowest, highest,
                    verdict.name().toLowerCase(java.util.Locale.ROOT), why);
        }
    }

    /** The Tm of {@code oligo} on {@code strand}, both 5' to 3', °C; 0 when they form no duplex. */
    static double tm(String oligo, String strand) {
        Thal.Result result = Thal.thal(oligo, strand, REACTION);
        return result.found() ? result.tm() : Math.min(0, result.tm());
    }

    /** The Tm of {@code oligo} on its perfect complement, °C. */
    static double perfectTm(String oligo) {
        return tm(oligo, Seq.reverseComplement(oligo));
    }

    /**
     * Judges the site of {@code oligo} whose template reads {@code site} in the oligo's own sense (the oligo itself
     * for a perfect site).
     *
     * @param before the template bases before the site's 5' end, in the oligo's sense (the last one beside the site)
     * @param after  the template bases after its 3' end, likewise (the first one beside the site)
     * @param primer whether the 3' rule applies: not to a probe
     */
    Site judge(String oligo, String site, String before, String after, boolean primer) {
        double footprint = tm(oligo, Seq.reverseComplement(site));
        double lowest = footprint;
        double highest = footprint;
        for (int left = 0; left <= before.length(); left++) {
            for (int right = 0; right <= after.length(); right++) {
                double tm = tm(oligo, Seq.reverseComplement(before.substring(before.length() - left) + site
                        + after.substring(0, right)));
                lowest = Math.min(lowest, tm);
                highest = Math.max(highest, tm);
            }
        }
        if (primer) {
            int tail = Math.min(window, Math.min(oligo.length(), site.length()));
            if (!site.endsWith(oligo.substring(oligo.length() - tail))) {
                return new Site(Verdict.REJECT, footprint, lowest, highest, "3' rule");
            }
        }
        if (lowest >= minimumTm + guard) {
            return new Site(Verdict.ACCEPT, footprint, lowest, highest, "Tm");
        }
        if (highest <= minimumTm - guard) {
            return new Site(Verdict.REJECT, footprint, lowest, highest, "Tm");
        }
        return new Site(Verdict.BORDERLINE, footprint, lowest, highest, "Tm about the minimum, by the template"
                + " bases taken under the oligo");
    }
}
