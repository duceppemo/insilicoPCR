package ca.canada.inspection.pcr;

import java.util.Comparator;
import java.util.Objects;

/**
 * One product as {@code report.tsv} lists it: an amplicon of one primer pair on one
 * contig (or read), with the probe that confirms it when the assay has one, and where the
 * call comes from. Products are passed between the steps of a run as these, and turned into
 * text only when the report is written (by {@code report.ReportColumn}).
 *
 * @param assay   the {@code Gene} column
 * @param site    where the amplicon lies
 * @param forward how the forward primer binds
 * @param reverse how the reverse primer binds
 * @param probe   the probe within the amplicon, or {@code null} for an assay without one
 * @param source  what the call was made on: a contig of the assembly, reads, or extended reads
 */
public record ReportRow(String sample, String assay, Site site, Binding forward, Binding reverse, Probe probe,
                        Source source) {

    public ReportRow {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(forward, "forward");
        Objects.requireNonNull(reverse, "reverse");
        Objects.requireNonNull(source, "source");
    }

    /**
     * Where an amplicon lies.
     *
     * @param start       first base of the amplicon on the contig ({@code GenomeLocation})
     * @param end         last base of the amplicon on the contig
     * @param contig      the contig (or read) it lies on
     * @param description the {@code Contig Description} column: the contig's FASTA description,
     *                    or for a call from reads what it rests on
     * @param strand      {@code +} when the forward primer matches the plus strand
     */
    public record Site(int start, int end, int size, String contig, String description, char strand) {
    }

    /**
     * How one primer binds.
     *
     * @param mismatches mismatches and indels, as counted against {@code -m}
     * @param threePrime mismatches in the primer's 3' window
     * @param unpaired   bases at the primer's 5' end that do not pair with the template
     */
    public record Binding(String primer, int mismatches, int threePrime, int unpaired) {
    }

    /**
     * A probe hit within the amplicon.
     *
     * @param start first base of the probe on the contig
     * @param end   last base
     */
    public record Probe(String id, int start, int end, int mismatches) {
        public int size() {
            return end - start + 1;
        }
    }

    /** What a call was made on. */
    public sealed interface Source {
        /** A contig of the sample's assembly (or mini-assembly). */
        Source ASSEMBLY = new Assembly();

        /** A contig of the sample's assembly (or mini-assembly). */
        record Assembly() implements Source {
        }

        /**
         * Reads that each hold the whole amplicon (a FASTQ read-level call).
         *
         * @param supporting  reads or read pairs holding the amplicon
         * @param informative reads that could have held it
         * @param percent     their share, as the report writes it (e.g. {@code 63%})
         */
        record Reads(int supporting, int informative, String percent) implements Source {
        }

        /**
         * Reads extended through the baited reads (a FASTQ read-extension call).
         *
         * @param forwardClosing extended reads seeded from the forward primer that reach the reverse one
         * @param forwardSeeds   reads seeded from the forward primer
         */
        record Extension(int forwardClosing, int forwardSeeds, int reverseClosing, int reverseSeeds)
                implements Source {
        }
    }

    /**
     * Which of several rows of one product best shows how the oligos bind: fewest primer
     * mismatches, then fewest probe mismatches. A degenerate probe is searched as several
     * variants, and more than one can hit the same place within the mismatch budget; the
     * variant that matches best is the one to show. Rows that compare equal still differ
     * (another read, another variant): callers add their own order after this one, and
     * end it with {@link #VARIANT_ORDER}.
     */
    public static final Comparator<ReportRow> BEST_BOUND = Comparator
            .comparingInt((ReportRow row) -> row.forwardMismatches() + row.reverseMismatches())
            .thenComparingInt(ReportRow::probeMismatches);

    /**
     * A last, arbitrary but fixed order among rows that differ only by which variant of a
     * degenerate primer or probe they name, so that the same run always shows the same one.
     */
    public static final Comparator<ReportRow> VARIANT_ORDER = Comparator
            .comparing(ReportRow::forwardPrimer)
            .thenComparing(ReportRow::reversePrimer)
            .thenComparing(ReportRow::probeId);

    public int start() {
        return site.start();
    }

    public int end() {
        return site.end();
    }

    public int size() {
        return site.size();
    }

    public String contig() {
        return site.contig();
    }

    public String contigDescription() {
        return site.description();
    }

    public char strand() {
        return site.strand();
    }

    public String forwardPrimer() {
        return forward.primer();
    }

    public String reversePrimer() {
        return reverse.primer();
    }

    public int forwardMismatches() {
        return forward.mismatches();
    }

    public int reverseMismatches() {
        return reverse.mismatches();
    }

    public int forwardThreePrime() {
        return forward.threePrime();
    }

    public int reverseThreePrime() {
        return reverse.threePrime();
    }

    public int forwardUnpaired() {
        return forward.unpaired();
    }

    public int reverseUnpaired() {
        return reverse.unpaired();
    }

    /** The {@code GenomeLocation} column: {@code start-end}. */
    public String location() {
        return site.start() + "-" + site.end();
    }

    /** The probe ID, or the empty string when the row has none. */
    public String probeId() {
        return probe == null ? "" : probe.id();
    }

    /** The probe mismatches; 0 when the row has no probe. */
    public int probeMismatches() {
        return probe == null ? 0 : probe.mismatches();
    }

    /** The same amplicon, confirmed by this probe. */
    public ReportRow withProbe(Probe confirming) {
        return new ReportRow(sample, assay, site, forward, reverse, confirming, source);
    }

    /**
     * The same amplicon as a call from reads reports it: made on {@code callSource}, with
     * {@code description} (what the call rests on) as its {@code Contig Description}.
     */
    public ReportRow calledFrom(Source callSource, String description) {
        Site described = new Site(site.start(), site.end(), site.size(), site.contig(), description, site.strand());
        return new ReportRow(sample, assay, described, forward, reverse, probe, callSource);
    }
}
