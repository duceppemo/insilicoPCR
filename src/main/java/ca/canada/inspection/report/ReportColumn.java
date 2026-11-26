package ca.canada.inspection.report;

import ca.canada.inspection.pcr.ReportRow;

import java.util.List;
import java.util.function.Function;

/**
 * The columns of {@code report.tsv} that come from a {@link ReportRow}, in report order: the
 * name in the header and the text of a row. {@link ConsolidatedReport} writes the header and
 * the rows from this list, and {@link ReportTsv} finds the columns by these names, so the
 * three cannot disagree. The size check ({@code ExpectedSizes.COLUMN}), worked out from the
 * primer file rather than the row, follows them.
 *
 * <p>Since 1.0 every report has every column, in this order, empty where it does not apply (a
 * row without a probe; no size declared): the header is the format, and a column is never
 * inserted before another because of what the primer file holds. Columns renamed for 1.0 keep
 * their old names as {@link #aliases}, so that the program still reads earlier reports.</p>
 */
public enum ReportColumn {
    SAMPLE("Sample", ReportRow::sample),
    ASSAY("Assay", ReportRow::assay, "Gene"),
    LOCATION("GenomeLocation", ReportRow::location),
    SIZE("AmpliconSize", row -> String.valueOf(row.size())),
    CONTIG("Contig", ReportRow::contig),
    CONTIG_DESCRIPTION("ContigDescription", ReportRow::contigDescription, "Contig Description"),
    FORWARD_PRIMER("ForwardPrimer", ReportRow::forwardPrimer, "ForwardPrimers"),
    REVERSE_PRIMER("ReversePrimer", ReportRow::reversePrimer, "ReversePrimers"),
    FORWARD_MISMATCHES("ForwardMismatches", row -> String.valueOf(row.forwardMismatches())),
    REVERSE_MISMATCHES("ReverseMismatches", row -> String.valueOf(row.reverseMismatches())),
    FORWARD_THREE_PRIME("Forward3PrimeMismatches", row -> String.valueOf(row.forwardThreePrime())),
    REVERSE_THREE_PRIME("Reverse3PrimeMismatches", row -> String.valueOf(row.reverseThreePrime())),
    STRAND("Strand", row -> String.valueOf(row.strand())),
    /** The probe columns: empty for a row without a probe (before 1.0, left out when no assay had one). */
    PROBE("Probe", row -> row.probe() == null ? "" : row.probe().id()),
    PROBE_LOCATION("ProbeLocation", row -> row.probe() == null ? "" : row.probe().start() + "-" + row.probe().end()),
    PROBE_SIZE("ProbeSize", row -> row.probe() == null ? "" : String.valueOf(row.probe().size())),
    PROBE_MISMATCHES("ProbeMismatches", row -> row.probe() == null ? "" : String.valueOf(row.probe().mismatches())),
    /**
     * Per primer, the bases at its 5' end that do not pair with the template, within its first
     * {@link ca.canada.inspection.pcr.AmpliconOptions#FIVE_PRIME_FREE} bases: those BLAST left out of the alignment
     * that differ from the template (or lie beyond its end), and the mismatches it kept there. The mismatch columns
     * do not count them, so without these a primer
     * held by its last few bases reads as a perfect match. Named "Unaligned" before 1.0, when it
     * counted only bases BLAST left out.
     */
    FORWARD_UNPAIRED("Forward5PrimeUnpaired", row -> String.valueOf(row.forwardUnpaired()), "Forward5PrimeUnaligned"),
    REVERSE_UNPAIRED("Reverse5PrimeUnpaired", row -> String.valueOf(row.reverseUnpaired()), "Reverse5PrimeUnaligned"),
    /** How the primers came together: {@code designed}, {@code one-primer} or {@code two-assays}. */
    PAIRING("Pairing", row -> pairing(row.pairing())),
    /** What the call was made on: {@code assembly}, {@code reads} (read-level) or {@code extension}. */
    SOURCE("Source", row -> source(row.source())),
    /**
     * The Tm columns (since 2.0), after the size check: each oligo's Tm on its site, °C, as the Tm gate
     * ({@code --anneal-temp}) worked it out; empty without the gate, and the probe's for a row without a probe.
     */
    FORWARD_TM("ForwardTm", row -> tm(row.forwardTm())),
    REVERSE_TM("ReverseTm", row -> tm(row.reverseTm())),
    PROBE_TM("ProbeTm", row -> tm(row.probeTm()));

    /** The columns before the size check ({@code ExpectedSizes.COLUMN}), in report order: the columns of 1.x. */
    public static final List<ReportColumn> BEFORE_SIZE_CHECK = List.of(values()).subList(0, SOURCE.ordinal() + 1);

    /** The columns after the size check, in report order. */
    public static final List<ReportColumn> AFTER_SIZE_CHECK = List.of(values()).subList(SOURCE.ordinal() + 1,
            values().length);

    /** Every column, in report order (the size check between {@link #BEFORE_SIZE_CHECK} and the others). */
    public static final List<ReportColumn> ALL = List.of(values());

    /** A Tm as the report writes it: {@code 58.7}; empty for none ({@code NaN}). */
    public static String tm(double tm) {
        return Double.isNaN(tm) ? "" : String.format(java.util.Locale.ROOT, "%.1f", tm);
    }

    private final String header;
    private final Function<ReportRow, String> value;
    private final List<String> aliases;

    ReportColumn(String header, Function<ReportRow, String> value, String... aliases) {
        this.header = header;
        this.value = value;
        this.aliases = List.of(aliases);
    }

    /** The column's name in the header line. */
    public String header() {
        return header;
    }

    /** Names the column had in earlier releases, which {@link ReportTsv} still reads. */
    public List<String> aliases() {
        return aliases;
    }

    /** A pairing as the report writes it. */
    public static String pairing(ReportRow.Pairing pairing) {
        return switch (pairing) {
            case DESIGNED -> "designed";
            case ONE_PRIMER -> "one-primer";
            case TWO_ASSAYS -> "two-assays";
        };
    }

    /** A source as the report writes it. */
    public static String source(ReportRow.Source source) {
        return switch (source) {
            case ReportRow.Source.Assembly assembly -> "assembly";
            case ReportRow.Source.Reads reads -> "reads";
            case ReportRow.Source.Extension extension -> "extension";
        };
    }

    /** The row's value in this column, as one TSV cell. */
    public String value(ReportRow row) {
        return cell(value.apply(row));
    }


    /**
     * Text as one TSV cell: a tab or a line break (a FASTA header's description may hold one)
     * would start another column or row, so each becomes a space. Text between quotes (a file
     * named {@code "isolate 7".fasta}) is quoted as a spreadsheet quotes it, since the reader takes
     * such quotes off (they are a spreadsheet's): read back, it is {@link #readBack} of the text.
     */
    public static String cell(String text) {
        String cell = readBack(text);
        if (needsApostrophe(cell)) {
            // A contig description =HYPERLINK(...), a sample file named -ctrl.fasta: a spreadsheet opening the
            // report took them for formulas (#NAME?, or a link to follow). A leading apostrophe makes one show
            // the text as it is; the program's own reader (ReportTsv) takes it off.
            return "'" + cell;
        }
        return cell.length() >= 2 && cell.startsWith("\"") && cell.endsWith("\"")
                ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell;
    }

    /**
     * Whether {@code cell} is written after an apostrophe: a spreadsheet takes it for a formula, or it starts with
     * an apostrophe the reader would take off (a name {@code '-a} is written {@code ''-a}, read back {@code '-a}).
     */
    public static boolean needsApostrophe(String cell) {
        return formulaLike(cell) || cell.startsWith("'") && needsApostrophe(cell.substring(1));
    }

    /**
     * Whether a spreadsheet takes {@code cell} for a formula: it starts with {@code =} or {@code @}, or with
     * {@code +} or {@code -} and is not a number (a strand, {@code -}, and {@code -5} are left as they are).
     */
    public static boolean formulaLike(String cell) {
        if (cell.isEmpty()) {
            return false;
        }
        char first = cell.charAt(0);
        if (first == '=' || first == '@') {
            return true;
        }
        return (first == '+' || first == '-') && cell.length() > 1
                && !cell.matches("[+-]\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    }

    /** What a cell written from {@code text} reads back as: its tabs and line breaks are spaces. */
    public static String readBack(String text) {
        return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
