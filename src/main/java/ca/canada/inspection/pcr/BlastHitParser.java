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
 * <p>Acceptance rules (since 2.0): a site is accepted when its Tm on the template passes the {@link TmGate}, and
 * a primer's weighted penalty of imperfections inside the 3' window does not exceed {@code maxThreePrimePenalty}
 * (the 3' rule). Hydrolysis probes (-P) do not extend: the Tm alone decides for them.</p>
 *
 * <p>BLAST trims mismatches near the ends of its local alignments together with
 * the matching bases beyond them. When the caller supplies the template around a
 * hit, the trimmed bases are compared with it first (see {@link TrimmedEnds}):
 * trimmed probe bases and primer 3' bases then count only where they do not pair.</p>
 */
public final class BlastHitParser {

    private static final int EXPECTED_FIELDS = BlastField.values().length;

    private BlastHitParser() {
    }

    /** Parses a hit without templates: trimmed bases are judged as BLAST left them. */
    public static Optional<BlastResult> parse(String line, PrimerPanel primers, AmpliconOptions options) {
        return parse(line, primers, options, hit -> null);
    }

    /**
     * Parses a hit and applies the acceptance rules, one site at a time ({@link BlastHits#read} computes the Tms of
     * a search in parallel, with the same test: {@link Pending#accepted}).
     *
     * @param templates the query sequence (contig or read) a hit lies on, or {@code null}
     *                  when unknown; asked only for hits with bases BLAST left out, in the order of the report
     */
    public static Optional<BlastResult> parse(String line, PrimerPanel primers, AmpliconOptions options,
                                              Function<BlastResult, TrimmedEnds.Template> templates) {
        TmGate gate = options.tmGate();
        return parseSite(line, primers, options, templates).flatMap(site -> site.acceptedHit(gate, site.tm(gate)));
    }

    /**
     * A hit with the template strand its oligo anneals to ({@link #parseSite}): the Tm, the costly part (a
     * thermodynamic alignment), can then be computed apart, in parallel.
     *
     * @param strand            the template strand under the site; {@code null} for a site that fails the 3' rule,
     *                          which needs no Tm
     * @param threePrimePenalty the 3' penalty of a primer's site ({@code --3p-penalty}); 0 for a probe
     * @param primes            whether it passes the 3' rule (a probe always does: it does not extend)
     */
    public record Pending(BlastResult hit, String strand, int threePrimePenalty, boolean primes) {
        /**
         * The Tm of the site, °C ({@link TmGate#siteTm(BlastResult, String)}); {@link Double#NEGATIVE_INFINITY} for
         * an oligo without a sequence, and for a site that fails the 3' rule, which has no strand: it needs no Tm.
         */
        public double tm(TmGate gate) {
            return gate.siteTm(hit, strand);
        }

        /** Whether the rules accept the site, its Tm being {@code tm}: the one acceptance test. */
        public boolean accepted(TmGate gate, double tm) {
            return primes && gate.accepts(hit.subjectID(), tm);
        }

        /**
         * The hit, with its Tm {@code tm}, when the rules accept it ({@link #accepted}); empty otherwise. What
         * {@link BlastHitParser#parse} and {@link BlastHits#read} keep.
         */
        public Optional<BlastResult> acceptedHit(TmGate gate, double tm) {
            return accepted(gate, tm) ? Optional.of(hit.withTm(tm)) : Optional.empty();
        }
    }

    /**
     * The hit of {@code line}, its trimmed bases compared with the template, with the strand under it and whether it
     * passes the 3' rule; empty for a line that is not a hit. The 3' rule costs nothing, so a site that fails it
     * needs no Tm; it is kept, for what the run says of an oligo without an accepted site (its best site, and why
     * that one fails).
     */
    public static Optional<Pending> parseSite(String line, PrimerPanel primers, AmpliconOptions options,
                                              Function<BlastResult, TrimmedEnds.Template> templates) {
        return hit(line, primers).map(found -> site(found, options, templates));
    }

    /** The hit of a line of blastn's table, as BLAST aligned it; empty for a line that is not one. */
    public static Optional<BlastResult> hit(String line, PrimerPanel primers) {
        return toHit(line, primers);
    }

    /** {@link #parseSite} of a hit already read ({@link #hit}). */
    public static Pending site(BlastResult found, AmpliconOptions options,
                               Function<BlastResult, TrimmedEnds.Template> templates) {
        // The template is read only under a site with bases BLAST left out: to compare them, and for the strand under
        // them (the Tm gate pairs the oligo with every base of the site). A site BLAST aligned whole has its strand in
        // the alignment; asking for its template would also count it in a missing sequence's warning.
        boolean trimmed = found.fivePrimeUncovered() + found.threePrimeUncovered() > 0 || found.qseq() == null;
        TrimmedEnds.Template template = trimmed ? templates.apply(found) : null;
        BlastResult hit = TrimmedEnds.needsTemplate(found)
                ? TrimmedEnds.extend(found, template, AmpliconOptions.FIVE_PRIME_FREE) : found;
        return judged(hit, options, template);
    }

    /**
     * The 3' rule and the strand of a hit whose trimmed bases were compared with {@code template} (or could not
     * be: {@code null}).
     */
    private static Pending judged(BlastResult hit, AmpliconOptions options, TrimmedEnds.Template template) {
        if (PrimerId.isProbe(hit.subjectID())) {
            return new Pending(hit, TmGate.strandUnder(hit, template), 0, true);
        }
        hit = hit.withFivePrimeFree(AmpliconOptions.FIVE_PRIME_FREE);
        int penalty = hit.threePrimePenalty(options.threePrimeWindow());
        boolean primes = penalty <= options.maxThreePrimePenalty();
        return new Pending(hit, primes ? TmGate.strandUnder(hit, template) : null, penalty, primes);
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
        return toHit(line, primers).flatMap(hit -> cutByQueryEnd(hit, options, templates));
    }

    /** {@link #cutByQueryEnd} of a hit already read ({@link #hit}). */
    public static Optional<BlastResult> cutByQueryEnd(BlastResult found, AmpliconOptions options,
                                                      Function<BlastResult, TrimmedEnds.Template> templates) {
        int fivePrimeFree = AmpliconOptions.FIVE_PRIME_FREE;
        return Optional.of(found)
                .filter(hit -> !PrimerId.isProbe(hit.subjectID()) && TrimmedEnds.needsTemplate(hit))
                .flatMap(hit -> {
                    TrimmedEnds.Template template = templates.apply(hit);
                    BlastResult onQuery = TrimmedEnds.extend(hit, template, fivePrimeFree).withFivePrimeFree(fivePrimeFree);
                    BlastResult paired = TrimmedEnds.pairedPastTheEnd(onQuery, template);
                    return paired != null && alignedBases(onQuery) >= minimumOnQuery(onQuery.slen())
                            && !accepts(onQuery, options, template) && accepts(paired, options, template)
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

    /** Applies the acceptance rules to an already-parsed hit, whose template is not known. */
    public static boolean accepts(BlastResult hit, AmpliconOptions options) {
        return accepts(hit, options, null);
    }

    /**
     * Applies the acceptance rules to an already-parsed hit, its trimmed bases already compared.
     *
     * @param template the sequence the hit lies on, or {@code null}; the Tm gate reads the bases BLAST left out there
     */
    public static boolean accepts(BlastResult hit, AmpliconOptions options, TrimmedEnds.Template template) {
        Pending site = judged(hit, options, template);
        return site.accepted(options.tmGate(), site.tm(options.tmGate()));
    }
}
