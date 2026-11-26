package ca.canada.inspection.model;

/**
 * The best site an oligo has that the Tm rule rejected (since 2.0): what the run says of an assay without a product.
 *
 * @param hit                 the site, with its Tm ({@link BlastResult#tm}); {@code NaN} for a site that fails the 3'
 *                            rule, whose Tm is not worked out
 * @param threePrimePenalty   its 3' penalty ({@code --3p-penalty}); 0 for a probe
 * @param neededTm            the Tm a site of the oligo needs, °C; {@code NaN} when unknown
 * @param failsThreePrimeRule whether the site fails the 3' rule
 */
public record NearMiss(BlastResult hit, int threePrimePenalty, double neededTm, boolean failsThreePrimeRule) {

    /** How far below the Tm it needs a site may melt and still look like the oligo's site ({@link #plausible}), °C. */
    public static final double PLAUSIBLE_TM_RANGE = 10;

    /**
     * How many bases a site failing the 3' rule may differ from its oligo by and still look like the oligo's site
     * ({@link #plausible}).
     */
    public static final int PLAUSIBLE_DIFFERENCES = 3;

    /** A site that passes the 3' rule and melts at {@code tm} °C, below the {@code neededTm} it needs. */
    public static NearMiss of(BlastResult hit, double tm, int threePrimePenalty, double neededTm) {
        return new NearMiss(hit.withTm(tm), threePrimePenalty, neededTm, false);
    }

    /** {@link #of(BlastResult, double, int, double)} of a site whose needed Tm is unknown. */
    public static NearMiss of(BlastResult hit, double tm, int threePrimePenalty) {
        return of(hit, tm, threePrimePenalty, Double.NaN);
    }

    /** A site that fails the 3' rule, with a 3' penalty of {@code threePrimePenalty}: its Tm is not worked out. */
    public static NearMiss failingThreePrimeRule(BlastResult hit, int threePrimePenalty, double neededTm) {
        return new NearMiss(hit.withTm(Double.NaN), threePrimePenalty, neededTm, true);
    }

    /** {@link #failingThreePrimeRule(BlastResult, int, double)} of a site whose needed Tm is unknown. */
    public static NearMiss failingThreePrimeRule(BlastResult hit, int threePrimePenalty) {
        return failingThreePrimeRule(hit, threePrimePenalty, Double.NaN);
    }

    /** The site's Tm, °C; {@code NaN} when it was not worked out (it fails the 3' rule). */
    public double tm() {
        return hit.tm();
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
        return Double.isNaN(neededTm) || tm() >= neededTm - PLAUSIBLE_TM_RANGE;
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
            return tm() > other.tm();
        }
        if (threePrimePenalty != other.threePrimePenalty) {
            return threePrimePenalty < other.threePrimePenalty;
        }
        return hit.length() - hit.mismatch() > other.hit.length() - other.hit.mismatch();
    }
}
