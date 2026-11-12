package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
 *
 * <p>BLAST trims mismatches near the ends of its local alignments together with
 * the matching bases beyond them. For probes, the bases it trimmed are read from
 * the template and counted (see {@link ProbeEnds}) when the caller supplies the
 * template sequences; callers find which ones they need with {@link #templateNeeded}.</p>
 */
public final class BlastHitParser {

    private static final int EXPECTED_FIELDS = 15;

    /**
     * Probe bases that may lie beyond the end of the template (a contig or read
     * edge) and so cannot be compared; bases the template has are always compared
     * and counted against the probe budget. Intentionally independent of the primer
     * {@code --5p-truncation} knob.
     */
    private static final int MAX_PROBE_END_UNCOVERED = 2;

    private BlastHitParser() {
    }

    /** Parses a hit without template sequences: trimmed probe ends stay unaligned. */
    public static Optional<BlastResult> parse(String line, String sampleName,
                                              Map<String, String> primers, AmpliconOptions options) {
        return parse(line, sampleName, primers, options, queryId -> null);
    }

    /**
     * Parses a hit and applies the acceptance rules.
     *
     * @param templates sequence of a query (contig or read) by its BLAST {@code qseqid},
     *                  or {@code null} when unknown; asked only for the IDs
     *                  {@link #templateNeeded} reports
     */
    public static Optional<BlastResult> parse(String line, String sampleName,
                                              Map<String, String> primers, AmpliconOptions options,
                                              Function<String, String> templates) {
        return toHit(line, sampleName, primers)
                .map(hit -> ProbeEnds.needsTemplate(hit)
                        ? ProbeEnds.extend(hit, templates.apply(hit.queryID()))
                        : hit)
                .filter(hit -> accepts(hit, options));
    }

    /**
     * The query ID whose sequence {@link #parse} needs for this line: present for a
     * probe hit with bases BLAST left out of the alignment.
     */
    public static Optional<String> templateNeeded(String line, Map<String, String> primers) {
        return toHit(line, "", primers)
                .filter(ProbeEnds::needsTemplate)
                .map(BlastResult::queryID);
    }

    private static Optional<BlastResult> toHit(String line, String sampleName, Map<String, String> primers) {
        String[] fields = line.split("\t", -1);
        if (fields.length < EXPECTED_FIELDS) {
            return Optional.empty();
        }

        String sseqid = fields[1];
        String primerSeq = primers.get(sseqid);
        if (primerSeq == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(new BlastResult(
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
            ));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Applies the acceptance rules to an already-parsed hit. */
    public static boolean accepts(BlastResult hit, AmpliconOptions options) {
        if (isProbe(hit.subjectID())) {
            // Probes anneal without extension: no 3'-specific rules. Bases left
            // unaligned here are bases the template does not have (or that were not
            // looked up); trimmed bases the template has were already compared.
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
