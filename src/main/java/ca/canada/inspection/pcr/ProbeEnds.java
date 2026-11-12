package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;

/**
 * Restores the probe bases that BLAST trimmed off the ends of a local alignment.
 *
 * <p>{@code blastn-short} scores a match +1 and a mismatch -3, so a local alignment
 * never keeps a mismatch followed by three or fewer matching bases at its end: a
 * single mismatch at probe position 1-4 (or the mirror positions at the other end)
 * is cut off together with the matching bases beyond it. Those bases are not
 * counted as mismatches, so a mismatch at positions 1-2 read as a perfect match,
 * and the cut at positions 3-4 left more unaligned bases than a probe may have, so
 * the hit was rejected whatever the probe mismatch budget.</p>
 *
 * <p>Given the template the hit sits on, {@link #extend} adds the trimmed bases back
 * as ungapped alignment columns and counts the ones that differ as mismatches.
 * Only bases the template does not have (a probe hanging off the end of a contig or
 * read) stay unaligned.</p>
 */
public final class ProbeEnds {

    private ProbeEnds() {
    }

    /** True when the hit is a probe hit with bases BLAST left out of the alignment. */
    public static boolean needsTemplate(BlastResult hit) {
        return BlastHitParser.isProbe(hit.subjectID())
                && hit.fivePrimeUncovered() + hit.threePrimeUncovered() > 0;
    }

    /**
     * Extends a probe hit over its unaligned ends.
     *
     * @param hit      the BLAST hit; returned unchanged unless {@link #needsTemplate} holds
     * @param template the full sequence of the hit's query (contig or read), in query
     *                 orientation; {@code null} when it is not available
     * @return the hit with every probe base the template covers aligned, and its
     * mismatch count, coordinates and aligned sequences updated to match
     */
    public static BlastResult extend(BlastResult hit, String template) {
        if (template == null || !needsTemplate(hit) || hit.primerSeq() == null
                || hit.primerSeq().length() != hit.slen()) {
            return hit;
        }
        boolean plus = hit.isPlusStrand();
        // Probe bases missing before qstart and after qend, in query coordinates.
        int wantLeft = plus ? hit.sstart() - 1 : hit.slen() - hit.sstart();
        int wantRight = plus ? hit.slen() - hit.send() : hit.send() - 1;
        int left = Math.clamp(wantLeft, 0, Math.max(0, hit.qstart() - 1));
        int right = Math.clamp(wantRight, 0, Math.max(0, template.length() - hit.qend()));
        if (left == 0 && right == 0) {
            return hit;
        }

        StringBuilder qLeft = new StringBuilder(left);
        StringBuilder sLeft = new StringBuilder(left);
        StringBuilder qRight = new StringBuilder(right);
        StringBuilder sRight = new StringBuilder(right);
        int extraMismatches = 0;

        // Query position qstart - k pairs with probe position sstart - k (plus) or
        // sstart + k (minus); qend + k pairs with send + k (plus) or send - k (minus).
        for (int k = left; k >= 1; k--) {
            char q = templateBase(template, hit.qstart() - k);
            char s = probeBaseInQueryOrientation(hit, plus ? hit.sstart() - k : hit.sstart() + k, plus);
            qLeft.append(q);
            sLeft.append(s);
            extraMismatches += pairs(q, s) ? 0 : 1;
        }
        for (int k = 1; k <= right; k++) {
            char q = templateBase(template, hit.qend() + k);
            char s = probeBaseInQueryOrientation(hit, plus ? hit.send() + k : hit.send() - k, plus);
            qRight.append(q);
            sRight.append(s);
            extraMismatches += pairs(q, s) ? 0 : 1;
        }

        return new BlastResult(
                hit.name(),
                hit.queryID(),
                hit.subjectID(),
                hit.mismatch() + extraMismatches,
                hit.gaps(),
                hit.qstart() - left,
                hit.qend() + right,
                plus ? hit.sstart() - left : hit.sstart() + left,
                plus ? hit.send() + right : hit.send() - right,
                hit.slen(),
                hit.length() + left + right,
                hit.qseq() == null ? null : qLeft + hit.qseq() + qRight,
                hit.sseq() == null ? null : sLeft + hit.sseq() + sRight,
                hit.primerSeq());
    }

    private static char templateBase(String template, int position) {
        return Character.toUpperCase(template.charAt(position - 1));
    }

    /** Probe base at 1-based probe position, as it appears aligned to the query's plus strand. */
    private static char probeBaseInQueryOrientation(BlastResult hit, int probePosition, boolean plus) {
        char base = Character.toUpperCase(hit.primerSeq().charAt(probePosition - 1));
        return plus ? base : complement(base);
    }

    /** Ambiguous or unknown template bases (N, IUPAC codes) count as mismatches. */
    private static boolean pairs(char queryBase, char subjectBase) {
        return queryBase == subjectBase && "ACGT".indexOf(queryBase) >= 0;
    }

    private static char complement(char base) {
        return switch (base) {
            case 'A' -> 'T';
            case 'C' -> 'G';
            case 'G' -> 'C';
            case 'T' -> 'A';
            default -> 'N';
        };
    }
}
