package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.BlastField;
import ca.canada.inspection.util.FastaScanner;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * The FASTA file given to {@code blastn} as its query.
 *
 * <p>A file that starts with a byte order mark is copied without it, and on Windows, a file whose
 * path is too long for BLAST+ is copied ({@link #of(Path, Path, boolean)}).</p>
 *
 * <p>{@code blastn} does not read gzipped FASTA: given a {@code .gz} file it stops with
 * "BLAST query error". A gzipped assembly is therefore decompressed to a file that lasts
 * as long as the search; any other file is searched where it is.</p>
 *
 * <p>An assembly with a sequence longer than {@link #PIECE_LENGTH} is searched in overlapping pieces
 * ({@link #toContig} puts the hits back on the sequence). BLAST's e-value grows with the length of the
 * sequence searched: on a chromosome, a primer site within {@code -m 2} or {@code -m 3} (16 bases
 * aligned with 2 mismatches, say) got an e-value above the cut-off and was dropped, which a draft of
 * the same genome, in contigs of a megabase or less, kept. On a 48 Mb chromosome: none of 5,689 such
 * sites whole, all of them in pieces, for 3 times the search time (as for the draft).</p>
 */
final class BlastQuery implements AutoCloseable {
    private static final String PREFIX = "blast_query_";
    /** The decompressed copies this class writes, as a file name pattern. */
    static final String LEFT_BEHIND = PREFIX + "*.fasta";
    /** The longest name such a copy can have ({@link Files#createTempFile}: the prefix, a number, the suffix). */
    static final String LONGEST_NAME = PREFIX + Long.toUnsignedString(-1L) + ".fasta";

    /** The longest sequence searched whole: about the longest contig of a draft assembly. */
    static final int PIECE_LENGTH = 1_000_000;
    /** How much two pieces of a sequence share: more than any oligo, so each primer site is whole in one. */
    static final int PIECE_OVERLAP = 1_000;
    /** The ID of a piece in the query: this, then its number ({@link #toContig} names its sequence again). */
    static final String PIECE_ID = "insilicoPCR_piece_";

    private final Path path;
    private final boolean temporary;
    /** Each piece of the query by its number: its sequence's ID, and where in it the piece starts. */
    private final List<Piece> pieces;

    private record Piece(String contig, int offset) {
    }

    private BlastQuery(Path path, boolean temporary, List<Piece> pieces) {
        this.path = path;
        this.temporary = temporary;
        this.pieces = List.copyOf(pieces);
    }

    /**
     * The query for {@code fasta}: the file itself, or its decompressed copy in
     * {@code scratchDirectory} when its name ends with {@code .gz}.
     */
    static BlastQuery of(Path fasta, Path scratchDirectory) throws IOException {
        return of(fasta, scratchDirectory, WindowsPathLimit.tooLong(fasta));
    }

    /**
     * @param tooLong whether {@code blastn} cannot open {@code fasta} where it is: on Windows, BLAST+
     *                cannot open a path of 260 characters or more ("File is not accessible"), which Java
     *                reads and writes, so a FASTA file in a deep folder is searched from a copy
     */
    static BlastQuery of(Path fasta, Path scratchDirectory, boolean tooLong) throws IOException {
        boolean gzipped = fasta.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz");
        boolean pieces;
        try {
            pieces = SequenceFileUtils.sequenceLengths(fasta).values().stream().anyMatch(length -> length > PIECE_LENGTH);
        } catch (UncheckedIOException e) {
            throw new IOException("Unable to " + (gzipped ? "decompress " : "read ") + fasta + ": "
                    + e.getCause().getMessage(), e.getCause());
        }
        if (pieces) {
            return inPieces(fasta, scratchDirectory);
        }
        if (!gzipped && !tooLong && !startsWithByteOrderMark(fasta)) {
            return new BlastQuery(fasta, false, List.of());
        }
        Path plain = Files.createTempFile(scratchDirectory, PREFIX, ".fasta");
        // The raw stream is a resource of its own: closed even when the gzip header cannot be read.
        try (InputStream raw = Files.newInputStream(fasta);
             InputStream in = SequenceFileUtils.withoutByteOrderMark(gzipped ? new GZIPInputStream(raw) : raw)) {
            Files.copy(in, plain, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(plain);
            throw new IOException("Unable to " + (gzipped ? "decompress " : "copy ") + fasta + ": " + e.getMessage(), e);
        }
        return new BlastQuery(plain, true, List.of());
    }

    /**
     * A copy of {@code fasta} whose sequences longer than {@link #PIECE_LENGTH} are pieces of that length,
     * each sharing {@link #PIECE_OVERLAP} bases with the next; the others are copied whole. Read as the
     * input check reads it (gzipped or not, past a byte order mark), its bases only: their positions are
     * BLAST's.
     */
    private static BlastQuery inPieces(Path fasta, Path scratchDirectory) throws IOException {
        Path copy = Files.createTempFile(scratchDirectory, PREFIX, ".fasta");
        List<Piece> pieces = new ArrayList<>();
        try (FastaScanner scanner = new FastaScanner(List.of(fasta));
             Writer out = Files.newBufferedWriter(copy, StandardCharsets.UTF_8)) {
            String header = scanner.nextHeader(null);
            while (header != null) {
                PieceWriter sequence = new PieceWriter(out, header, pieces);
                header = scanner.nextHeader(sequence::accept);
                sequence.finish();
            }
        } catch (IOException | UncheckedIOException e) {
            Files.deleteIfExists(copy);
            throw new IOException("Unable to copy " + fasta + " in pieces: " + e.getMessage(), e);
        }
        return new BlastQuery(copy, true, pieces);
    }

    /** Writes one sequence of the copy: whole, or in pieces once it passes {@link #PIECE_LENGTH}. */
    private static final class PieceWriter {
        private final Writer out;
        private final String header;
        private final String contig;
        private final List<Piece> pieces;
        private final StringBuilder window = new StringBuilder();
        private int offset;
        private boolean split;

        PieceWriter(Writer out, String header, List<Piece> pieces) {
            this.out = out;
            this.header = header;
            this.contig = SequenceFileUtils.fastaId(header);
            this.pieces = pieces;
        }

        void accept(int base) {
            window.append((char) base);
            if (window.length() == PIECE_LENGTH) {
                piece();
                window.delete(0, PIECE_LENGTH - PIECE_OVERLAP);
                offset += PIECE_LENGTH - PIECE_OVERLAP;
                split = true;
            }
        }

        void finish() {
            if (!split) {
                write(header, window);
            } else if (window.length() > PIECE_OVERLAP) {
                piece(); // the rest, beyond the last piece's overlap
            }
        }

        private void piece() {
            write(">" + PIECE_ID + pieces.size(), window);
            pieces.add(new Piece(contig, offset));
        }

        private void write(String headerLine, CharSequence bases) {
            try {
                out.write(headerLine);
                out.write('\n');
                for (int i = 0; i < bases.length(); i += 80) {
                    out.append(bases, i, Math.min(bases.length(), i + 80));
                    out.write('\n');
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * {@code line}, a line of blastn's table for this query, as on the sequence searched: a piece's hit
     * named by its sequence's ID, at its positions there. Any other line as it is.
     */
    String toContig(String line) {
        if (pieces.isEmpty() || !line.startsWith(PIECE_ID)) {
            return line;
        }
        String[] fields = line.split("\t", -1);
        int tab = fields[BlastField.QSEQID.ordinal()].length();
        Piece piece = pieces.get(Integer.parseInt(line.substring(PIECE_ID.length(), tab)));
        fields[BlastField.QSEQID.ordinal()] = piece.contig();
        for (BlastField position : List.of(BlastField.QSTART, BlastField.QEND)) {
            fields[position.ordinal()] = String.valueOf(Integer.parseInt(fields[position.ordinal()]) + piece.offset());
        }
        return String.join("\t", fields);
    }

    /**
     * Whether {@code fasta} starts with a byte order mark: blastn takes it for a line of data and stops
     * ("CFastaReader: Near line 1, there's a line that doesn't look like plausible data"), and the
     * input check, which reads past it, had let the file through.
     */
    static boolean startsWithByteOrderMark(Path fasta) throws IOException {
        return SequenceFileUtils.startsWithByteOrderMark(fasta);
    }

    /** The file to pass to {@code blastn -query}. */
    Path path() {
        return path;
    }

    /** Removes the decompressed copy, if one was made. */
    @Override
    public void close() throws IOException {
        if (temporary) {
            Files.deleteIfExists(path);
        }
    }
}
