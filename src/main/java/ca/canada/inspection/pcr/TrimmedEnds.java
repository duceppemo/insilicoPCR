package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;

/**
 * Compares the oligo bases that BLAST trimmed off the ends of a local alignment
 * with the template.
 *
 * <p>{@code blastn-short} scores a match +1 and a mismatch -3, so a local alignment
 * never keeps a mismatch followed by three or fewer matching bases at its end: one
 * mismatch near an end is cut off together with the matching bases beyond it, and
 * the trimmed bases look like bases that do not pair. Given the template the hit
 * sits on, {@link #extend} reads them back:</p>
 * <ul>
 *   <li>probes: both ends are added back as ungapped alignment columns, and the
 *       bases that differ count as probe mismatches;</li>
 *   <li>primers, 3' end: the same, so one 3' mismatch counts once instead of once
 *       per trimmed base (in the total budget and the 3' penalty alike);</li>
 *   <li>primers, 5' end: the first {@code --5p-truncation} bases are left unaligned,
 *       and the mismatch budget does not count them (5'-tailed primers do not pair
 *       there on purpose); only the number of those bases that do not pair is
 *       recorded, for the report. Trimmed bases past them are added back like the
 *       3' end's, their mismatches counted: BLAST trims through a mismatch near
 *       the 5' end with another before it, and a site of two mismatches there was
 *       rejected where a site of one, past them, was not.</li>
 * </ul>
 * <p>Ambiguous template bases (N, IUPAC codes) do not pair. Bases beyond the end of
 * the template cannot be compared: they stay unaligned and are treated as before.</p>
 */
public final class TrimmedEnds {

    private TrimmedEnds() {
    }

    /** The bases of a whole query sequence (contig or read), position 1 first. */
    public record Template(CharSequence bases, int circle) {

        /** A linear sequence. */
        public Template(CharSequence bases) {
            this(bases, 0);
        }

        /** A whole sequence. */
        public static Template whole(String sequence) {
            return new Template(sequence);
        }

        /**
         * Whether there is a base at {@code position}: within the sequence, or anywhere on a circle
         * ({@code circle > 0}, its length as one), whose positions go on past its origin, either way.
         */
        boolean has(int position) {
            return circle > 0 || position >= 1 && position <= bases.length();
        }

        /** The base at {@code position}, upper case; U read as T, as BLAST pairs it. */
        char base(int position) {
            int at = circle > 0 ? Math.floorMod(position - 1, circle) : position - 1;
            char base = Character.toUpperCase(bases.charAt(at));
            return base == 'U' ? 'T' : base;
        }
    }

    /**
     * True when comparing the hit's trimmed bases with the template can change
     * whether it is accepted or what the report says about it: the hit has trimmed
     * bases and would pass every rule the comparison cannot relax.
     */
    public static boolean needsTemplate(BlastResult hit, AmpliconOptions options) {
        if (hit.fivePrimeUncovered() + hit.threePrimeUncovered() == 0 || hit.primerSeq() == null
                || hit.primerSeq().length() != hit.slen()) {
            return false;
        }
        return BlastHitParser.passesWhateverTheTrimmedBases(hit, options);
    }

    /**
     * The hit with its trimmed bases compared with the template.
     *
     * @param template the query sequence the hit lies on, in query orientation;
     *                 {@code null} leaves the hit unchanged
     */
    public static BlastResult extend(BlastResult hit, Template template) {
        return extend(hit, template, Integer.MAX_VALUE);
    }

    /**
     * As {@link #extend(BlastResult, Template)}, a primer's trimmed 5' bases past its first
     * {@code fivePrimeFree} ({@code --5p-truncation}) added back as alignment columns.
     */
    public static BlastResult extend(BlastResult hit, Template template, int fivePrimeFree) {
        if (template == null || hit.primerSeq() == null || hit.primerSeq().length() != hit.slen()
                || hit.fivePrimeUncovered() + hit.threePrimeUncovered() == 0) {
            return hit;
        }
        boolean plus = hit.isPlusStrand();
        boolean probe = PrimerId.isProbe(hit.subjectID());

        // The query side left of qstart holds the oligo's 5' end on the plus strand, its 3' end on the minus strand.
        Side left = side(hit, template, plus, true);
        Side right = side(hit, template, plus, false);
        // Bases added back on each side, nearest the alignment first: all compared ones, but on a primer's
        // 5' side only those past its first fivePrimeFree bases.
        int addLeft = left.compared();
        int addRight = right.compared();
        int fivePrimeUnpaired = -1;
        if (!probe) {
            Side fivePrime = plus ? left : right;
            int added = Math.min(fivePrime.compared(), Math.max(0, fivePrime.wanted() - fivePrimeFree));
            fivePrimeUnpaired = fivePrime.wanted() - fivePrime.compared() + fivePrime.mismatchesBeyond(added);
            if (plus) {
                addLeft = added;
            } else {
                addRight = added;
            }
        }
        if (addLeft == 0 && addRight == 0 && fivePrimeUnpaired < 0) {
            return hit;
        }

        int extraMismatches = left.mismatchesWithin(addLeft) + right.mismatchesWithin(addRight);
        String qseq = hit.qseq() == null ? null : left.query(addLeft) + hit.qseq() + right.query(addRight);
        String sseq = hit.sseq() == null ? null : left.subject(addLeft) + hit.sseq() + right.subject(addRight);
        return new BlastResult(
                hit.queryID(),
                hit.subjectID(),
                hit.mismatch() + extraMismatches,
                hit.gaps(),
                hit.qstart() - addLeft,
                hit.qend() + addRight,
                plus ? hit.sstart() - addLeft : hit.sstart() + addLeft,
                plus ? hit.send() + addRight : hit.send() - addRight,
                hit.slen(),
                hit.length() + addLeft + addRight,
                qseq,
                sseq,
                hit.primerSeq(),
                fivePrimeUnpaired);
    }

    /**
     * Trimmed bases on one query side of the alignment.
     *
     * @param left     whether the side is left of the alignment
     * @param wanted   oligo bases BLAST left out on this side
     * @param compared how many of them the template has (contiguous from the alignment edge)
     * @param unpaired for each compared base, nearest the alignment first, whether it does not pair
     * @param query    template bases of the compared positions, left to right
     * @param subject  oligo bases of the compared positions as aligned to the query's plus strand
     */
    private record Side(boolean left, int wanted, int compared, boolean[] unpaired, String query, String subject) {
        /** Unpaired bases among the {@code count} nearest the alignment. */
        int mismatchesWithin(int count) {
            int mismatches = 0;
            for (int k = 0; k < count; k++) {
                mismatches += unpaired[k] ? 1 : 0;
            }
            return mismatches;
        }

        /** Unpaired bases among the compared ones past the {@code count} nearest the alignment. */
        int mismatchesBeyond(int count) {
            return mismatchesWithin(compared) - mismatchesWithin(count);
        }

        /** Template bases of the {@code count} positions nearest the alignment, left to right. */
        String query(int count) {
            return left ? query.substring(compared - count) : query.substring(0, count);
        }

        /** Oligo bases of the {@code count} positions nearest the alignment, left to right. */
        String subject(int count) {
            return left ? subject.substring(compared - count) : subject.substring(0, count);
        }
    }

    private static Side side(BlastResult hit, Template template, boolean plus, boolean leftSide) {
        int wanted = leftSide
                ? (plus ? hit.sstart() - 1 : hit.slen() - hit.sstart())
                : (plus ? hit.slen() - hit.send() : hit.send() - 1);
        int compared = 0;
        while (compared < wanted && template.has(leftSide ? hit.qstart() - compared - 1 : hit.qend() + compared + 1)) {
            compared++;
        }
        char[] query = new char[compared];
        char[] subject = new char[compared];
        boolean[] unpaired = new boolean[compared];
        // Query position qstart - k pairs with oligo position sstart - k (plus) or sstart + k (minus);
        // qend + k pairs with send + k (plus) or send - k (minus).
        for (int k = 1; k <= compared; k++) {
            int queryPosition = leftSide ? hit.qstart() - k : hit.qend() + k;
            int oligoPosition = leftSide
                    ? (plus ? hit.sstart() - k : hit.sstart() + k)
                    : (plus ? hit.send() + k : hit.send() - k);
            char q = template.base(queryPosition);
            char s = oligoBaseInQueryOrientation(hit, oligoPosition, plus);
            int index = leftSide ? compared - k : k - 1;
            query[index] = q;
            subject[index] = s;
            unpaired[k - 1] = !pairs(q, s);
        }
        return new Side(leftSide, wanted, compared, unpaired, new String(query), new String(subject));
    }

    private static char oligoBaseInQueryOrientation(BlastResult hit, int oligoPosition, boolean plus) {
        char base = Character.toUpperCase(hit.primerSeq().charAt(oligoPosition - 1));
        return plus ? base : Dna.complement(base);
    }

    private static boolean pairs(char queryBase, char subjectBase) {
        return queryBase == subjectBase && "ACGT".indexOf(queryBase) >= 0;
    }

}
