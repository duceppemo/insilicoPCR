package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;

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

    private static final int EXPECTED_FIELDS = BlastField.values().length;

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
    public static Optional<BlastResult> parse(String line, PrimerPanel primers, AmpliconOptions options) {
        return parse(line, primers, options, hit -> null);
    }

    /**
     * Parses a hit and applies the acceptance rules.
     *
     * @param templates the query sequence (contig or read) a hit lies on, or {@code null}
     *                  when unknown; asked only for hits whose trimmed bases matter
     *                  ({@link TrimmedEnds#needsTemplate}), in the order of the report
     */
    public static Optional<BlastResult> parse(String line, PrimerPanel primers, AmpliconOptions options,
                                              Function<BlastResult, TrimmedEnds.Template> templates) {
        int fivePrimeFree = options.maxFivePrimeTruncation();
        return toHit(line, primers)
                .map(hit -> TrimmedEnds.needsTemplate(hit, options)
                        ? TrimmedEnds.extend(hit, templates.apply(hit), fivePrimeFree)
                        : hit)
                .map(hit -> PrimerId.isProbe(hit.subjectID()) ? hit : hit.withFivePrimeFree(fivePrimeFree))
                .filter(hit -> accepts(hit, options));
    }

    /**
     * A primer's hit {@link #parse} rejects only because its 3' end runs off the end of a linear query (a contig
     * ends inside its site), when it would be accepted were its bases past the end to pair: for the warning that
     * a product could span the break ({@link SplitAcrossContigs}), never for a product. The hit as on the query.
     * At least {@link #minimumOnQuery} of the primer's bases must be aligned on the query: a few bases at a contig's
     * end match some primer by chance (7 to 9 of them made every such warning on 2,022 genomes in 0.15.3). Its 5'
     * bases left out of the alignment do not count, though on the contig: with them, 8 aligned bases were 13.
     */
    public static Optional<BlastResult> cutByQueryEnd(String line, PrimerPanel primers, AmpliconOptions options,
                                                      Function<BlastResult, TrimmedEnds.Template> templates) {
        int fivePrimeFree = options.maxFivePrimeTruncation();
        return toHit(line, primers)
                .filter(hit -> !PrimerId.isProbe(hit.subjectID()) && TrimmedEnds.needsTemplate(hit, options))
                .flatMap(hit -> {
                    TrimmedEnds.Template template = templates.apply(hit);
                    BlastResult onQuery = TrimmedEnds.extend(hit, template, fivePrimeFree).withFivePrimeFree(fivePrimeFree);
                    BlastResult paired = TrimmedEnds.pairedPastTheEnd(onQuery, template);
                    return paired != null && alignedBases(onQuery) >= minimumOnQuery(onQuery.slen())
                            && !accepts(onQuery, options) && accepts(paired, options)
                            ? Optional.of(onQuery) : Optional.empty();
                });
    }

    /**
     * Fewest bases of a primer {@code primerLength} long that must be aligned on a contig for its site to be taken as cut
     * by the contig's end ({@link #cutByQueryEnd}): 12, or half the primer when that is more.
     */
    static int minimumOnQuery(int primerLength) {
        return Math.max(MIN_CUT_ON_QUERY, (primerLength + 1) / 2);
    }

    private static final int MIN_CUT_ON_QUERY = 12;

    /** The primer's bases in the alignment. */
    private static int alignedBases(BlastResult hit) {
        return Math.abs(hit.send() - hit.sstart()) + 1;
    }

    private static Optional<BlastResult> toHit(String line, PrimerPanel primers) {
        String[] fields = line.split("\t", -1);
        if (fields.length < EXPECTED_FIELDS) {
            return Optional.empty();
        }

        String sseqid = BlastField.SSEQID.of(fields);
        String primerSeq = primers.sequence(sseqid);
        if (primerSeq == null) {
            // The database holds the run's primers only: BLAST changed this one's ID (makeblastdb wrote a
            // letter outside ASCII as '#'). Skipped, every hit of the primer was lost without a word.
            throw new IllegalStateException("BLAST names an oligo the primer file does not hold: " + sseqid
                    + " (its ID was changed by BLAST)");
        }

        try {
            return Optional.of(new BlastResult(
                    BlastField.QSEQID.of(fields),   // the contig (or read)
                    sseqid,                         // the oligo
                    number(BlastField.MISMATCH, fields),
                    number(BlastField.GAPS, fields),
                    number(BlastField.QSTART, fields),
                    number(BlastField.QEND, fields),
                    number(BlastField.SSTART, fields),
                    number(BlastField.SEND, fields),
                    number(BlastField.SLEN, fields),
                    number(BlastField.LENGTH, fields), // alignment length
                    BlastField.QSEQ.of(fields),     // aligned query sequence
                    BlastField.SSEQ.of(fields),     // aligned subject sequence
                    primerSeq
            ));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static int number(BlastField field, String[] fields) {
        return Integer.parseInt(field.of(fields));
    }

    /** Applies the acceptance rules to an already-parsed hit. */
    public static boolean accepts(BlastResult hit, AmpliconOptions options) {
        if (!passesWhateverTheTrimmedBases(hit, options)) {
            return false;
        }
        if (PrimerId.isProbe(hit.subjectID())) {
            // Probes anneal without extension: no 3'-specific rules. Bases left
            // unaligned here are bases the template does not have (or that were not
            // looked up); trimmed bases the template has were already compared.
            return hit.fivePrimeUncovered() + hit.threePrimeUncovered() <= MAX_PROBE_END_UNCOVERED;
        }
        // Unaligned 3' bases left here could not be compared with the template (beyond
        // its end, or no template given): count them as mismatches, like aligned ones.
        return hit.fivePrimeUncovered() <= options.maxFivePrimeTruncation()
                && hit.primerMismatches() <= options.maxTotalMismatches()
                && hit.threePrimePenalty(options.threePrimeWindow()) <= options.maxThreePrimePenalty();
    }

    /**
     * The acceptance rules that comparing the bases BLAST trimmed with the template cannot change
     * (it only counts trimmed bases as pairing or not): the mismatches in the alignment, and a
     * primer's unaligned 5' bases. A hit that fails them is rejected without that comparison
     * ({@link TrimmedEnds#needsTemplate}). A rule added to {@link #accepts} belongs here when the
     * comparison cannot relax it, after it otherwise.
     */
    static boolean passesWhateverTheTrimmedBases(BlastResult hit, AmpliconOptions options) {
        if (PrimerId.isProbe(hit.subjectID())) {
            return hit.alignedMismatches() <= options.maxProbeMismatches();
        }
        // A primer's aligned mismatches past its first --5p-truncation bases count, whatever the trimmed
        // bases are; its trimmed 5' bases past them are compared with the template (TrimmedEnds), so
        // their number decides only when the template does not have them (accepts).
        return hit.alignedMismatchesPast(options.maxFivePrimeTruncation()) <= options.maxTotalMismatches();
    }
}
