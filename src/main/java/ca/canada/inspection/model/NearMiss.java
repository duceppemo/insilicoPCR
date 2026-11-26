package ca.canada.inspection.model;

/**
 * The best site an oligo has that the Tm rule rejected (since 2.0): what the run says of an assay without a product.
 *
 * @param hit               the site
 * @param tm                its Tm, °C; {@code NaN} when it was not worked out: the site fails the 3' rule
 * @param threePrimePenalty its 3' penalty ({@code --3p-penalty}); 0 for a probe
 * @param neededTm          the Tm a site of the oligo needs, °C; {@code NaN} when unknown
 */
public record NearMiss(BlastResult hit, double tm, int threePrimePenalty, double neededTm) {

    /** How far below the Tm it needs a site may melt and still look like the oligo's site ({@link #plausible}), °C. */
    public static final double PLAUSIBLE_TM_RANGE = 10;

    /**
     * How many bases a site failing the 3' rule may differ from its oligo by and still look like the oligo's site
     * ({@link #plausible}).
     */
    public static final int PLAUSIBLE_DIFFERENCES = 3;

    /** A site whose needed Tm is unknown. */
    public NearMiss(BlastResult hit, double tm, int threePrimePenalty) {
        this(hit, tm, threePrimePenalty, Double.NaN);
    }

    /** Whether the site fails the 3' rule (its Tm was then not worked out). */
    public boolean failsThreePrimeRule() {
        return Double.isNaN(tm);
    }

    /**
     * Whether the site looks like a site of the oligo, not a chance BLAST match of a few bases: it melts within
     * {@link #PLAUSIBLE_TM_RANGE} of the Tm it needs (any Tm when that is unknown), or it fails the 3' rule only,
     * differing from the oligo by at most {@link #PLAUSIBLE_DIFFERENCES} bases.
     */
    public boolean plausible() {
        if (failsThreePrimeRule()) {
            return hit.alignedMismatches() + hit.fivePrimeUnpairedBases() + hit.threePrimeUncovered()
                    <= PLAUSIBLE_DIFFERENCES;
        }
        return Double.isNaN(neededTm) || tm >= neededTm - PLAUSIBLE_TM_RANGE;
    }

    /**
     * Whether this site is closer to being accepted than {@code other}: a {@link #plausible} site before one that is
     * not (a real site with a 3' mismatch before a chance match melting at 10 °C); then a site that passes the 3'
     * rule before one that does not; then the higher Tm; between two that fail the 3' rule, the lower penalty, then
     * the longer alignment.
     */
    public boolean closerThan(NearMiss other) {
        if (plausible() != other.plausible()) {
            return plausible();
        }
        if (failsThreePrimeRule() != other.failsThreePrimeRule()) {
            return !failsThreePrimeRule();
        }
        if (!failsThreePrimeRule()) {
            return tm > other.tm;
        }
        if (threePrimePenalty != other.threePrimePenalty) {
            return threePrimePenalty < other.threePrimePenalty;
        }
        return hit.length() - hit.mismatch() > other.hit.length() - other.hit.mismatch();
    }
}
