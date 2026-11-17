package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.util.Circles;

import java.util.Optional;

/**
 * Decides whether a forward and a reverse primer hit form a productive amplicon.
 *
 * <p>A pair is only reported when the two primers sit on the same contig, on
 * opposite strands, in convergent orientation (3' ends pointing toward each
 * other), and when the resulting product length falls within the configured
 * size limits.</p>
 *
 * <p>On a circular sequence, a pair whose plus-strand primer lies past the minus-strand one also makes
 * a product: round the origin (since 0.15.3). Its location starts after it ends ({@code 1780-279}).</p>
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
        return call(forward, reverse, options, 0);
    }

    /**
     * @param circle the sequence's length as a circle ({@link ca.canada.inspection.util.Circles}), or 0 for
     *               a linear one
     * @return the amplicon, or empty when the pair cannot amplify
     */
    public static Optional<Amplicon> call(BlastResult forward, BlastResult reverse, AmpliconOptions options,
                                          int circle) {
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
        // minus-strand primer. Overlapping primers (very short products) are allowed. On a circle,
        // one past the other is upstream of it once round the origin: its positions go on from there.
        // The primers in full, as the product counts them: by the bases BLAST aligned, a primer whose
        // unaligned 5' bases reach past the other's 3' end faced away, and its short product was dropped.
        // Those 5' bases count only where they pair with the template: the other primer's 3' end on 5'
        // bases that do not pair would sit on a mismatch in the strand the first primer made.
        int round = 0;
        if (pairedFivePrimeEdge(rightExtending) > leftExtending.fullStart()
                || rightExtending.fullEnd() > pairedFivePrimeEdge(leftExtending)) {
            if (circle <= 0) {
                return Optional.empty();
            }
            round = circle;
        }

        // The full primers are incorporated into the product: it is one primer, the template
        // between the two primers' 3' ends, and the other primer. Its size counts each primer in
        // full, 5' bases BLAST left unaligned and bases beyond the contig (a primer hanging off
        // a contig end) included, and not the template under it: an insertion or deletion in a
        // primer site is not in the product (before 0.15.3 it changed the size by its length).
        // The location is the template's, from one primer's 5' terminus to the other's, clamped
        // at the contig start (the contig end is unknown here).
        int productStart = rightExtending.fivePrimeTerminus();
        int end = leftExtending.fivePrimeTerminus() + round;
        int rightThreePrime = rightExtending.qend() + rightExtending.threePrimeUncovered();
        int leftThreePrime = leftExtending.qstart() - leftExtending.threePrimeUncovered() + round;
        int size = rightExtending.slen() + (leftThreePrime - rightThreePrime - 1) + leftExtending.slen();

        if (circle > 0 && size > circle) {
            return Optional.empty(); // round the circle more than once
        }
        if (size < options.minAmpliconLength()) {
            return Optional.empty();
        }
        if (options.maxAmpliconLength() > 0 && size > options.maxAmpliconLength()) {
            return Optional.empty();
        }

        char strand = forward.isPlusStrand() ? '+' : '-';
        if (circle > 0) {
            return Optional.of(new Amplicon(forward.queryID(), Circles.onCircle(productStart, circle),
                    Circles.onCircle(end, circle),
                    size, strand, circle));
        }
        return Optional.of(new Amplicon(forward.queryID(), Math.max(1, productStart), end, size, strand, 0));
    }

    /**
     * Query position of the primer's 5'-most base known to pair with the template: the full primer's
     * when its unaligned 5' bases were compared with the template and all pair, the alignment's
     * otherwise (left edge on the plus strand, right edge on the minus strand).
     */
    private static int pairedFivePrimeEdge(BlastResult hit) {
        boolean paired = hit.fivePrimeUnpaired() == 0;
        if (hit.isPlusStrand()) {
            return paired ? hit.fullStart() : hit.qstart();
        }
        return paired ? hit.fullEnd() : hit.qend();
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
     * @param size          product length in bp: both primers in full and the template between
     *                      their 3' ends; larger than {@code end - start + 1} when a primer hangs
     *                      off the contig start, and unlike it when a primer site has an indel
     * @param forwardStrand '+' when the forward primer matches the plus strand, '-' otherwise
     * @param circle        the sequence's length as a circle, or 0 for a linear one; on a circle, a product
     *                      round the origin starts after it ends ({@code start > end})
     */
    public record Amplicon(String contig, int start, int end, int size, char forwardStrand, int circle) {

        /** Whether the product crosses the origin of its circle. */
        public boolean roundTheOrigin() {
            return circle > 0 && start > end;
        }

        /**
         * Whether the stretch from {@code positionStart} to {@code positionEnd} is within the product; on a
         * circle, a stretch may run on past the origin ({@code positionEnd} beyond the circle).
         */
        public boolean contains(int positionStart, int positionEnd) {
            int from = positionStart;
            int to = positionEnd;
            int last = end;
            if (roundTheOrigin()) {
                last = end + circle;
                if (from < start) {
                    from += circle;
                    to += circle;
                }
            }
            return from >= start && to <= last;
        }
    }
}
