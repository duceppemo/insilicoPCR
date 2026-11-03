package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;

/** Builders for BLAST hits used across the amplicon tests. */
final class TestHits {

    static final String PRIMER_20 = "ACGTACGTACGTACGTACGT"; // 20-mer ending in T
    static final String PRIMER_GC = "ACGTACGTACGTACGTACGC"; // 20-mer ending in C

    private TestHits() {
    }

    /** Perfect full-length hit on the plus strand: primer occupies [qstart, qstart+19]. */
    static BlastResult plusHit(String primerId, String contig, int qstart) {
        return plusHit(primerId, contig, qstart, PRIMER_20, PRIMER_20);
    }

    /** Full-length plus-strand hit with explicit aligned sequences. */
    static BlastResult plusHit(String primerId, String contig, int qstart, String qseq, String sseq) {
        int slen = sseq.replace("-", "").length();
        return new BlastResult("sample", contig, primerId,
                countMismatches(qseq, sseq), countGaps(qseq, sseq),
                qstart, qstart + qseq.replace("-", "").length() - 1,
                1, slen, slen, sseq.length(), qseq, sseq, sseq.replace("-", ""));
    }

    /** Perfect full-length hit on the minus strand: primer occupies [qstart, qstart+19]. */
    static BlastResult minusHit(String primerId, String contig, int qstart) {
        return minusHit(primerId, contig, qstart, PRIMER_20, PRIMER_20);
    }

    /** Full-length minus-strand hit (sstart > send) with explicit aligned sequences. */
    static BlastResult minusHit(String primerId, String contig, int qstart, String qseq, String sseq) {
        int slen = sseq.replace("-", "").length();
        return new BlastResult("sample", contig, primerId,
                countMismatches(qseq, sseq), countGaps(qseq, sseq),
                qstart, qstart + qseq.replace("-", "").length() - 1,
                slen, 1, slen, sseq.length(), qseq, sseq, sseq.replace("-", ""));
    }

    /** Hit whose alignment covers primer positions [sstart, send] only (plus strand). */
    static BlastResult truncatedPlusHit(String primerId, String contig, int qstart,
                                        int sstart, int send, String primerSeq) {
        String aligned = primerSeq.substring(sstart - 1, send);
        return new BlastResult("sample", contig, primerId,
                0, 0, qstart, qstart + aligned.length() - 1,
                sstart, send, primerSeq.length(), aligned.length(), aligned, aligned, primerSeq);
    }

    private static int countMismatches(String qseq, String sseq) {
        int mismatches = 0;
        for (int i = 0; i < qseq.length(); i++) {
            char q = qseq.charAt(i);
            char s = sseq.charAt(i);
            if (q != '-' && s != '-' && q != s) {
                mismatches++;
            }
        }
        return mismatches;
    }

    private static int countGaps(String qseq, String sseq) {
        int gaps = 0;
        for (int i = 0; i < qseq.length(); i++) {
            if (qseq.charAt(i) == '-' || sseq.charAt(i) == '-') {
                gaps++;
            }
        }
        return gaps;
    }
}
