package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;

import java.util.Map;
import java.util.Optional;

/**
 * Parses one line of the BLAST TSV output and applies the hit acceptance rules.
 *
 * <p>Expected {@code -outfmt} columns:
 * {@code qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq}
 * where the query is the sample sequence and the subject is the primer.</p>
 *
 * <p>Acceptance rules for primers (-F/-R): mismatches, indels, and unaligned 3'
 * bases must fit within the total mismatch budget, at most
 * {@code maxFivePrimeTruncation} bases may be missing at the 5' end, and the
 * weighted penalty of imperfections inside the 3' window must not exceed
 * {@code maxThreePrimePenalty}. Hydrolysis probes (-P) do not extend, so only
 * the total mismatch budget and overall coverage are enforced for them.</p>
 */
public final class BlastHitParser {

    private static final int EXPECTED_FIELDS = 15;

    /**
     * Probes must cover nearly their whole length to report fluorescence; this
     * is intentionally independent of the primer {@code --5p-truncation} knob.
     */
    private static final int MAX_PROBE_END_UNCOVERED = 2;

    private BlastHitParser() {
    }

    public static Optional<BlastResult> parse(String line, String sampleName,
                                              Map<String, String> primers, AmpliconOptions options) {
        String[] fields = line.split("\t", -1);
        if (fields.length < EXPECTED_FIELDS) {
            return Optional.empty();
        }

        String sseqid = fields[1];
        String primerSeq = primers.get(sseqid);
        if (primerSeq == null) {
            return Optional.empty();
        }

        BlastResult hit;
        try {
            hit = new BlastResult(
                    sampleName,
                    fields[0],                      // qseqid (contig)
                    sseqid,                         // primer ID
                    Integer.parseInt(fields[3]),    // mismatch
                    Integer.parseInt(fields[4]),    // gaps
                    Integer.parseInt(fields[9]),    // qstart
                    Integer.parseInt(fields[10]),   // qend
                    Integer.parseInt(fields[12]),   // sstart
                    Integer.parseInt(fields[13]),   // send
                    Integer.parseInt(fields[7]),    // slen
                    Integer.parseInt(fields[8]),    // alignment length
                    fields[11],                     // aligned query sequence
                    fields[14],                     // aligned subject sequence
                    primerSeq
            );
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        return accepts(hit, options) ? Optional.of(hit) : Optional.empty();
    }

    /** Applies the acceptance rules to an already-parsed hit. */
    public static boolean accepts(BlastResult hit, AmpliconOptions options) {
        if (isProbe(hit.subjectID())) {
            // Probes anneal without extension: no 3'-specific rules, but the
            // alignment must cover nearly the whole probe.
            int uncovered = hit.fivePrimeUncovered() + hit.threePrimeUncovered();
            return hit.mismatch() + hit.gaps() <= options.maxProbeMismatches()
                    && uncovered <= MAX_PROBE_END_UNCOVERED;
        }

        // Unaligned 3' bases are bases that stopped pairing (BLAST trims terminal
        // mismatches), so they consume the mismatch budget like aligned mismatches.
        int effectiveMismatches = hit.mismatch() + hit.gaps() + hit.threePrimeUncovered();
        if (effectiveMismatches > options.maxTotalMismatches()) {
            return false;
        }
        if (hit.fivePrimeUncovered() > options.maxFivePrimeTruncation()) {
            return false;
        }
        return hit.threePrimePenalty(options.threePrimeWindow()) <= options.maxThreePrimePenalty();
    }

    /** True when the primer ID names a probe, e.g. {@code assay-P} or {@code assay-P_3}. */
    public static boolean isProbe(String primerId) {
        int lastDash = primerId.lastIndexOf('-');
        return lastDash >= 0 && lastDash < primerId.length() - 1 && primerId.charAt(lastDash + 1) == 'P';
    }
}
