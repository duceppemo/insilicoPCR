package ca.canada.inspection.model;

/**
 * The best site an oligo has that the Tm rule rejected (since 2.0): what the run says of an assay without a product.
 *
 * @param hit               the site
 * @param tm                its Tm, °C; {@code NaN} when it was not worked out: the site fails the 3' rule
 * @param threePrimePenalty its 3' penalty ({@code --3p-penalty}); 0 for a probe
 */
public record NearMiss(BlastResult hit, double tm, int threePrimePenalty) {

    /** Whether the site fails the 3' rule (its Tm was then not worked out). */
    public boolean failsThreePrimeRule() {
        return Double.isNaN(tm);
    }

    /**
     * Whether this site is closer to being accepted than {@code other}: a site that passes the 3' rule before one
     * that does not; then the higher Tm; between two that fail the 3' rule, the lower penalty, then the longer
     * alignment.
     */
    public boolean closerThan(NearMiss other) {
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
