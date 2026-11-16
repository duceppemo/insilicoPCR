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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /**
     * The start of a piece's ID in the query, before a token no sequence of the file starts with and the
     * piece's number ({@link #toContig} names its sequence again): a contig of the user's named like a piece
     * had its hits moved onto the long sequence, or stopped the run.
     */
    static final String PIECE_ID = "insilicoPCR_piece_";

    private final Path path;
    private final boolean temporary;
    /** Each piece of the query by its ID: its sequence's ID, where in it the piece starts, what it owns. */
    private final Map<String, Piece> pieces;

    /**
     * A piece of {@code contig} starting after its {@code offset} bases, which owns the hits starting from
     * {@code ownedFrom} to {@code ownedTo} of it (its own positions): each half of an overlap is the hits of
     * one piece only. The other piece holds them too, but may hold one cut by its end, which BLAST aligns
     * with 5' bases trimmed off: a mismatch it left out passed as an unaligned 5' base, a site of one
     * mismatch had none, and one hit was listed twice.
     */
    private record Piece(String contig, int offset, int ownedFrom, int ownedTo) {
        /** Owns no more than half the overlap with the piece before it. */
        static Piece of(String contig, int offset) {
            return new Piece(contig, offset, offset == 0 ? 1 : PIECE_OVERLAP / 2 + 1, Integer.MAX_VALUE);
        }

        /** The same piece, with a piece after it, which owns the second half of their overlap. */
        Piece followed() {
            return new Piece(contig, offset, ownedFrom, PIECE_LENGTH - PIECE_OVERLAP / 2);
        }

        boolean owns(int qstart) {
            return qstart >= ownedFrom && qstart <= ownedTo;
        }
    }

    private BlastQuery(Path path, boolean temporary, Map<String, Piece> pieces) {
        this.path = path;
        this.temporary = temporary;
        this.pieces = Map.copyOf(pieces);
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
        Map<String, Integer> lengths;
        try {
            lengths = SequenceFileUtils.sequenceLengths(fasta);
        } catch (UncheckedIOException e) {
            throw new IOException("Unable to " + (gzipped ? "decompress " : "read ") + fasta + ": "
                    + e.getCause().getMessage(), e.getCause());
        }
        if (lengths.values().stream().anyMatch(length -> length > PIECE_LENGTH)) {
            return inPieces(fasta, scratchDirectory, pieceIdPrefix(lengths.keySet()));
        }
        if (!gzipped && !tooLong && !startsWithByteOrderMark(fasta)) {
            return new BlastQuery(fasta, false, Map.of());
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
        return new BlastQuery(plain, true, Map.of());
    }

    /** {@link #PIECE_ID} and a token: the start of no ID of {@code ids}. */
    static String pieceIdPrefix(java.util.Set<String> ids) {
        java.util.Random random = new java.util.Random();
        while (true) {
            String prefix = PIECE_ID + Integer.toHexString(random.nextInt(0x10000000, Integer.MAX_VALUE)) + "_";
            if (ids.stream().noneMatch(id -> id.startsWith(prefix))) {
                return prefix;
            }
        }
    }

    /**
     * A copy of {@code fasta} whose sequences longer than {@link #PIECE_LENGTH} are pieces of that length,
     * each sharing {@link #PIECE_OVERLAP} bases with the next; the others are copied whole. Read as the
     * input check reads it (gzipped or not, past a byte order mark), its bases only: their positions are
     * BLAST's.
     */
    private static BlastQuery inPieces(Path fasta, Path scratchDirectory, String idPrefix) throws IOException {
        Path copy = Files.createTempFile(scratchDirectory, PREFIX, ".fasta");
        Map<String, Piece> pieces = new java.util.HashMap<>();
        try (FastaScanner scanner = new FastaScanner(List.of(fasta));
             Writer out = Files.newBufferedWriter(copy, StandardCharsets.UTF_8)) {
            String header = scanner.nextHeader(null);
            while (header != null) {
                PieceWriter sequence = new PieceWriter(out, header, idPrefix, pieces);
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
        private final String idPrefix;
        private final Map<String, Piece> pieces;
        private final StringBuilder window = new StringBuilder();
        private int offset;
        private boolean split;
        /** The ID of the piece written last, of this sequence. */
        private String last;

        PieceWriter(Writer out, String header, String idPrefix, Map<String, Piece> pieces) {
            this.out = out;
            this.header = header;
            this.contig = SequenceFileUtils.fastaId(header);
            this.idPrefix = idPrefix;
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
            if (last != null) {
                pieces.put(last, pieces.get(last).followed());
            }
            last = idPrefix + pieces.size();
            write(">" + last, window);
            pieces.put(last, Piece.of(contig, offset));
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
     * named by its sequence's ID, at its positions there, or {@code null} when the piece does not own it
     * (the piece next to it does: {@link Piece}). Any other line as it is.
     */
    String toContig(String line) {
        if (pieces.isEmpty()) {
            return line;
        }
        int tab = line.indexOf('\t');
        Piece piece = tab < 0 ? null : pieces.get(line.substring(0, tab));
        if (piece == null) {
            return line;
        }
        String[] fields = line.split("\t", -1);
        if (fields.length <= BlastField.QEND.ordinal()) {
            return line; // not a hit: the parser rejects it
        }
        int qstart;
        int qend;
        try {
            qstart = Integer.parseInt(fields[BlastField.QSTART.ordinal()]);
            qend = Integer.parseInt(fields[BlastField.QEND.ordinal()]);
        } catch (NumberFormatException e) {
            return line;
        }
        if (!piece.owns(Math.min(qstart, qend))) {
            return null;
        }
        fields[BlastField.QSEQID.ordinal()] = piece.contig();
        fields[BlastField.QSTART.ordinal()] = String.valueOf(qstart + piece.offset());
        fields[BlastField.QEND.ordinal()] = String.valueOf(qend + piece.offset());
        return String.join("\t", fields);
    }

    /** The IDs of the pieces of the query, in their order ({@link #toContig}). */
    List<String> pieceIds() {
        return pieces.keySet().stream().sorted(java.util.Comparator.comparingInt(
                id -> Integer.parseInt(id.substring(id.lastIndexOf('_') + 1)))).toList();
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
