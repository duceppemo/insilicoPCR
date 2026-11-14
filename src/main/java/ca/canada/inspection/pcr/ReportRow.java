package ca.canada.inspection.pcr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One product as {@code report.tsv} lists it: an amplicon of one primer pair on one
 * contig (or read), with the probe that confirms it when the assay has one. Products are
 * passed between the steps of a run as these, and turned into text only when the report
 * is written ({@link #columns}).
 *
 * @param assay                   the {@code Gene} column
 * @param start                   first base of the amplicon on the contig ({@code GenomeLocation})
 * @param end                     last base of the amplicon on the contig
 * @param forwardMismatches       mismatches of the forward primer, as counted against {@code -m}
 * @param forwardThreePrime       mismatches in the forward primer's 3' window
 * @param strand                  {@code +} when the forward primer matches the plus strand
 * @param probe                   the probe within the amplicon, or {@code null} for an assay without one
 * @param forwardUnpaired         bases at the forward primer's 5' end that do not pair with the template
 */
public record ReportRow(String sample, String assay, int start, int end, int size, String contig,
                        String contigDescription, String forwardPrimer, String reversePrimer,
                        int forwardMismatches, int reverseMismatches, int forwardThreePrime, int reverseThreePrime,
                        char strand, Probe probe, int forwardUnpaired, int reverseUnpaired) {

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

    /** The {@code GenomeLocation} column: {@code start-end}. */
    public String location() {
        return start + "-" + end;
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
        return new ReportRow(sample, assay, start, end, size, contig, contigDescription, forwardPrimer, reversePrimer,
                forwardMismatches, reverseMismatches, forwardThreePrime, reverseThreePrime, strand, confirming,
                forwardUnpaired, reverseUnpaired);
    }

    /** The same row with another {@code Contig Description}, as a call from reads reports it. */
    public ReportRow withContigDescription(String description) {
        return new ReportRow(sample, assay, start, end, size, contig, description, forwardPrimer, reversePrimer,
                forwardMismatches, reverseMismatches, forwardThreePrime, reverseThreePrime, strand, probe,
                forwardUnpaired, reverseUnpaired);
    }

    /**
     * The row's columns in report order, as text.
     *
     * @param probeColumns whether the report has the probe columns (any assay of the run has a
     *                     probe); they are empty for a row without one
     */
    public List<String> columns(boolean probeColumns) {
        List<String> columns = new ArrayList<>(List.of(
                sample, assay, location(), String.valueOf(size), contig, contigDescription,
                forwardPrimer, reversePrimer, String.valueOf(forwardMismatches), String.valueOf(reverseMismatches),
                String.valueOf(forwardThreePrime), String.valueOf(reverseThreePrime), String.valueOf(strand)));
        if (probeColumns) {
            if (probe == null) {
                columns.addAll(List.of("", "", "", ""));
            } else {
                columns.addAll(List.of(probe.id(), probe.start() + "-" + probe.end(), String.valueOf(probe.size()),
                        String.valueOf(probe.mismatches())));
            }
        }
        columns.add(String.valueOf(forwardUnpaired));
        columns.add(String.valueOf(reverseUnpaired));
        return columns;
    }
}
