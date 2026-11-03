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

        int start = rightExtending.qstart();
        int end = leftExtending.qend();
        int size = end - start + 1;

        if (size < options.minAmpliconLength()) {
            return Optional.empty();
        }
        if (options.maxAmpliconLength() > 0 && size > options.maxAmpliconLength()) {
            return Optional.empty();
        }

        return Optional.of(new Amplicon(forward.queryID(), start, end, forward.isPlusStrand() ? '+' : '-'));
    }

    /**
     * A predicted PCR product.
     *
     * @param contig        contig/sequence the product sits on
     * @param start         1-based leftmost template position (5' end of the plus-strand primer)
     * @param end           1-based rightmost template position (5' end of the minus-strand primer)
     * @param forwardStrand '+' when the forward primer matches the plus strand, '-' otherwise
     */
    public record Amplicon(String contig, int start, int end, char forwardStrand) {
        public String location() {
            return start + "-" + end;
        }

        public int size() {
            return end - start + 1;
        }

        public boolean contains(int positionStart, int positionEnd) {
            return positionStart >= start && positionEnd <= end;
        }
    }
}
