package ca.canada.inspection.pcr;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The columns of the BLAST TSV the run asks {@code blastn} for, in order: the {@code -outfmt}
 * the pipeline passes ({@link #OUTFMT}), the header it writes ({@link #HEADER}) and the
 * fields {@link BlastHitParser} reads all come from this list.
 */
public enum BlastField {
    QSEQID, SSEQID, POSITIVE, MISMATCH, GAPS, EVALUE, BITSCORE, SLEN, LENGTH, QSTART, QEND, QSEQ, SSTART, SEND, SSEQ;

    /** The {@code -outfmt} argument: tabular, these columns. */
    public static final String OUTFMT = "6 " + names(" ");

    /** The header line written above the hits. */
    public static final String HEADER = names("\t");

    /** This column's value on a line split at its tabs. */
    public String of(String[] fields) {
        return fields[ordinal()];
    }

    private static String names(String separator) {
        return Arrays.stream(values()).map(field -> field.name().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.joining(separator));
    }
}
