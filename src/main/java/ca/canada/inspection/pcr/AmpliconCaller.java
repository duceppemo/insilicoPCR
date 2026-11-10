package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;

import java.util.Optional;

/**
 * Decides whether a forward and a reverse primer hit form a productive amplicon.
 *
 * <p>A pair is only reported when the two primers sit on the same contig, on
 * opposite strands, in convergent orientation (3' ends pointing toward each
 * other), and when the resulting product length falls within the configured
 * size limits.</p>
 */
public final class AmpliconCaller {

    private AmpliconCaller() {
    }

    /**
     * @param forward hit of the forward (-F) primer
     * @param reverse hit of the reverse (-R) primer
     * @return the amplicon, or empty when the pair cannot amplify
     */
    public static Optional<Amplicon> call(BlastResult forward, BlastResult reverse, AmpliconOptions options) {
        if (!forward.queryID().equals(reverse.queryID())) {
            return Optional.empty();
        }
        // Opposite strands are required: one primer extends rightward, the other leftward.
        if (forward.isPlusStrand() == reverse.isPlusStrand()) {
            return Optional.empty();
        }

        BlastResult rightExtending = forward.isPlusStrand() ? forward : reverse;
        BlastResult leftExtending = forward.isPlusStrand() ? reverse : forward;

        // Convergent orientation: the plus-strand primer must lie upstream of the
        // minus-strand primer. Overlapping primers (very short products) are allowed.
        if (rightExtending.qstart() > leftExtending.qstart() || rightExtending.qend() > leftExtending.qend()) {
            return Optional.empty();
        }

        // The full primer is incorporated into the product, so the product runs from one
        // primer's 5' terminus to the other's, including any 5' bases BLAST left
        // unaligned. Those bases can lie beyond the contig (a primer hanging off a contig
        // end): the size still counts them, since the product does not depend on where
        // the assembly stopped. Only the reported location is clamped at the contig start
        // (the contig end is unknown here).
        int productStart = rightExtending.qstart() - rightExtending.fivePrimeUncovered();
        int end = leftExtending.qend() + leftExtending.fivePrimeUncovered();
        int size = end - productStart + 1;

        if (size < options.minAmpliconLength()) {
            return Optional.empty();
        }
        if (options.maxAmpliconLength() > 0 && size > options.maxAmpliconLength()) {
            return Optional.empty();
        }

        return Optional.of(new Amplicon(forward.queryID(), Math.max(1, productStart), end, size,
                forward.isPlusStrand() ? '+' : '-'));
    }

    /**
     * A predicted PCR product.
     *
     * @param contig        contig/sequence the product sits on
     * @param start         1-based leftmost template position (5' terminus of the plus-strand
     *                      primer, including any 5' bases BLAST left unaligned), clamped at
     *                      the contig start
     * @param end           1-based rightmost template position (5' terminus of the minus-strand
     *                      primer, including any 5' bases BLAST left unaligned)
     * @param size          product length in bp, both primers counted in full; larger than
     *                      {@code end - start + 1} when a primer hangs off the contig start
     * @param forwardStrand '+' when the forward primer matches the plus strand, '-' otherwise
     */
    public record Amplicon(String contig, int start, int end, int size, char forwardStrand) {
        public String location() {
            return start + "-" + end;
        }

        public boolean contains(int positionStart, int positionEnd) {
            return positionStart >= start && positionEnd <= end;
        }
    }
}
