package ca.canada.inspection.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Reads FASTA files (gzipped or not) one after the other, a block of characters at a time.
 * Lines are never built: a contig written on a single line, hundreds of millions of
 * characters long, is read like a wrapped one. The reader of every assembly-sized FASTA.
 */
public final class FastaScanner implements AutoCloseable {
    private final List<Path> files;
    private int fileIndex = -1;
    private BufferedReader reader;
    private final char[] block = new char[1 << 16];
    private int filled;
    private int next;
    private boolean atLineStart = true;

    public FastaScanner(List<Path> files) {
        this.files = files;
    }

    /**
     * Reads on to the next header line and returns it, {@code >} included, or
     * {@code null} after the last record. Each base passed on the way is given to {@code bases},
     * or skipped when it is {@code null}: the nucleotide letters (IUPAC codes and U, either case),
     * the characters BLAST counts. Anything else (line ends, blanks, alignment gaps {@code -} and
     * {@code .}, digits, {@code *}) BLAST drops, and so does this: a position here is BLAST's.
     * So are comments, as BLAST reads them: the rest of a line after {@code ;}, and a line that
     * starts with {@code #} or {@code !}.
     */
    public String nextHeader(IntConsumer bases) throws IOException {
        int c;
        boolean comment = false;
        while ((c = read()) >= 0) {
            if (c == '\n' || c == '\r') {
                atLineStart = true;
                comment = false;
            } else if (!comment && atLineStart && c == '>') {
                return headerLine();
            } else if (comment || isComment(c, atLineStart)) {
                comment = true; // to the line's end
                atLineStart = false;
            } else {
                atLineStart = false;
                if (bases != null && isNucleotide(c)) {
                    bases.accept(c);
                }
            }
        }
        return null;
    }

    private static final boolean[] NUCLEOTIDE = new boolean[128];

    static {
        for (char c : "ACGTURYKMSWBDHVN".toCharArray()) {
            NUCLEOTIDE[c] = true;
            NUCLEOTIDE[Character.toLowerCase(c)] = true;
        }
    }

    /** Whether BLAST counts {@code c} as a base of a nucleotide sequence. */
    static boolean isNucleotide(int c) {
        return c < NUCLEOTIDE.length && NUCLEOTIDE[c];
    }

    /** The rest of a header line whose {@code >} was just read. */
    private String headerLine() throws IOException {
        StringBuilder header = new StringBuilder(">");
        int file = fileIndex;
        int c;
        while ((c = read()) >= 0 && c != '\n' && c != '\r') {
            if (fileIndex != file) {
                // The file ended without a line end: this character starts the next file.
                next--;
                break;
            }
            header.append((char) c);
        }
        atLineStart = true;
        return header.toString();
    }

    /** Whether {@code c} starts a comment, as BLAST reads FASTA: {@code ;} anywhere, {@code #} or {@code !} at a line's start. */
    public static boolean isComment(int c, boolean atLineStart) {
        return c == ';' || atLineStart && (c == '#' || c == '!');
    }

    /** The next character across the files, or -1 after the last one. A new file starts a new line. */
    private int read() throws IOException {
        while (next >= filled) {
            if (reader == null) {
                if (fileIndex + 1 >= files.size()) {
                    return -1;
                }
                fileIndex++;
                reader = SequenceFileUtils.openMaybeGzip(files.get(fileIndex));
                atLineStart = true;
            }
            filled = reader.read(block);
            next = 0;
            if (filled <= 0) {
                filled = 0;
                reader.close();
                reader = null;
            }
        }
        return block[next++];
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }
}
