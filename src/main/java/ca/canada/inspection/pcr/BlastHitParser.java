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
 * <p>Acceptance rules for primers (-F/-R): mismatches, indels, and 3' bases that
 * do not pair with the template must fit within the total mismatch budget, at most
 * {@code maxFivePrimeTruncation} bases may be missing at the 5' end, and the
 * weighted penalty of imperfections inside the 3' window must not exceed
 * {@code maxThreePrimePenalty}. Hydrolysis probes (-P) do not extend, so only
 * the total mismatch budget and overall coverage are enforced for them.</p>
 *
 * <p>BLAST trims mismatches near the ends of its local alignments together with
 * the matching bases beyond them. When the caller supplies the template around a
 * hit, the trimmed bases are compared with it first (see {@link TrimmedEnds}):
 * trimmed probe bases and primer 3' bases then count only where they do not pair.</p>
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

    /** Parses a hit without templates: trimmed bases are judged as BLAST left them. */
    public static Optional<BlastResult> parse(String line, String sampleName,
                                              Map<String, String> primers, AmpliconOptions options) {
        return parse(line, sampleName, primers, options, hit -> null);
    }

    /**
     * Parses a hit and applies the acceptance rules.
     *
     * @param templates the query sequence (contig or read) a hit lies on, or {@code null}
     *                  when unknown; asked only for hits whose trimmed bases matter
     *                  ({@link TrimmedEnds#needsTemplate}), in the order of the report
     */
    public static Optional<BlastResult> parse(String line, String sampleName,
                                              Map<String, String> primers, AmpliconOptions options,
                                              Function<BlastResult, TrimmedEnds.Template> templates) {
        return toHit(line, sampleName, primers)
                .map(hit -> TrimmedEnds.needsTemplate(hit, options)
                        ? TrimmedEnds.extend(hit, templates.apply(hit))
                        : hit)
                .filter(hit -> accepts(hit, options));
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

        // Unaligned 3' bases left here could not be compared with the template (beyond
        // its end, or no template given): count them as mismatches, like aligned ones.
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
