package ca.canada.inspection.report;

import ca.canada.inspection.pcr.ReportRow;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * The columns of {@code report.tsv} that come from a {@link ReportRow}, in report order: the
 * name in the header and the text of a row. {@link ConsolidatedReport} writes the header and
 * the rows from this list, and {@link ReportTsv} finds the columns by these names, so the
 * three cannot disagree. The size check ({@code ExpectedSizes.COLUMN}), worked out from the
 * primer file rather than the row, follows them when the primer file declares sizes.
 */
public enum ReportColumn {
    SAMPLE("Sample", ReportRow::sample),
    GENE("Gene", ReportRow::assay),
    LOCATION("GenomeLocation", ReportRow::location),
    SIZE("AmpliconSize", row -> String.valueOf(row.size())),
    CONTIG("Contig", ReportRow::contig),
    CONTIG_DESCRIPTION("Contig Description", ReportRow::contigDescription),
    FORWARD_PRIMERS("ForwardPrimers", ReportRow::forwardPrimer),
    REVERSE_PRIMERS("ReversePrimers", ReportRow::reversePrimer),
    FORWARD_MISMATCHES("ForwardMismatches", row -> String.valueOf(row.forwardMismatches())),
    REVERSE_MISMATCHES("ReverseMismatches", row -> String.valueOf(row.reverseMismatches())),
    FORWARD_THREE_PRIME("Forward3PrimeMismatches", row -> String.valueOf(row.forwardThreePrime())),
    REVERSE_THREE_PRIME("Reverse3PrimeMismatches", row -> String.valueOf(row.reverseThreePrime())),
    STRAND("Strand", row -> String.valueOf(row.strand())),
    /** The probe columns, present when an assay of the run has a probe; empty for a row without one. */
    PROBE("Probe", row -> row.probe() == null ? "" : row.probe().id()),
    PROBE_LOCATION("ProbeLocation", row -> row.probe() == null ? "" : row.probe().start() + "-" + row.probe().end()),
    PROBE_SIZE("ProbeSize", row -> row.probe() == null ? "" : String.valueOf(row.probe().size())),
    PROBE_MISMATCHES("ProbeMismatches", row -> row.probe() == null ? "" : String.valueOf(row.probe().mismatches())),
    /**
     * Per primer, the bases at its 5' end that do not pair with the template: of the bases
     * BLAST left out of the alignment, those that differ from the template (or lie beyond its
     * end). The mismatch columns do not count them (the run's {@code --5p-truncation} allows
     * them), so without these a primer held by its last few bases reads as a perfect match.
     */
    FORWARD_UNALIGNED("Forward5PrimeUnaligned", row -> String.valueOf(row.forwardUnpaired())),
    REVERSE_UNALIGNED("Reverse5PrimeUnaligned", row -> String.valueOf(row.reverseUnpaired()));

    /**
     * The columns of every report, before the probe columns: what a file without a header
     * (saved from a spreadsheet without it) is read as.
     */
    public static final List<ReportColumn> FIRST = List.of(values()).subList(0, PROBE.ordinal());

    private final String header;
    private final Function<ReportRow, String> value;

    ReportColumn(String header, Function<ReportRow, String> value) {
        this.header = header;
        this.value = value;
    }

    /** The column's name in the header line. */
    public String header() {
        return header;
    }

    /** The row's value in this column, as one TSV cell. */
    public String value(ReportRow row) {
        return cell(value.apply(row));
    }

    /** Whether this is one of the probe columns. */
    public boolean isProbeColumn() {
        return ordinal() >= PROBE.ordinal() && ordinal() <= PROBE_MISMATCHES.ordinal();
    }

    /** The columns of a report, with or without the probe columns. */
    public static List<ReportColumn> of(boolean probeColumns) {
        return Arrays.stream(values()).filter(column -> probeColumns || !column.isProbeColumn()).toList();
    }

    /**
     * Text as one TSV cell: a tab or a line break (a FASTA header's description may hold one)
     * would start another column or row, so each becomes a space.
     */
    public static String cell(String text) {
        return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
