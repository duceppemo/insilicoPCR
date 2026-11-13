package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The query sequences (contigs or reads) a BLAST report refers to, read from the
 * FASTA files that were searched, so the trimmed ends of hits can be compared with
 * the template ({@link TrimmedEnds}).
 *
 * <p>BLAST writes the hits of each query together, in the order of the query file,
 * so the FASTA is read alongside the report: only the sequence of the current query
 * is held in memory, at one byte per base ({@link PackedBases}), however large the
 * assembly and however many hits it has. A
 * query asked for out of order is found by reading the files again from the start;
 * a query that is in none of them (its ID spelled differently by BLAST, say) gets no
 * template, and is looked for only once.</p>
 */
public final class QueryTemplates implements Function<BlastResult, TrimmedEnds.Template>, AutoCloseable {

    private final List<Path> files;
    private Scanner scanner;
    /** The header the scanner stopped at after the current record: the next record to look at. */
    private String pendingHeader;
    private String currentId;
    private TrimmedEnds.Template current;
    /** Every record ID of the files; built only once a query is not found reading forward. */
    private Set<String> allIds;
    private final Set<String> missing = new HashSet<>();

    public QueryTemplates(List<Path> fastaFiles) {
        this.files = List.copyOf(fastaFiles);
        this.scanner = new Scanner(files);
    }

    @Override
    public TrimmedEnds.Template apply(BlastResult hit) {
        String id = hit.queryID();
        if (id.equals(currentId)) {
            return current;
        }
        if (missing.contains(id)) {
            return null;
        }
        try {
            if (readForwardTo(id)) {
                return current;
            }
            if (allIds == null) {
                allIds = indexIds();
            }
            if (allIds.contains(id)) {
                // Asked for out of order: read again from the start.
                restart();
                if (readForwardTo(id)) {
                    return current;
                }
            }
            missing.add(id);
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the sequences searched: " + files, e);
        }
    }

    /** IDs of the queries that were asked for and are in none of the files. */
    public Set<String> missing() {
        return Set.copyOf(missing);
    }

    /** Reads on from the current position until the record {@code id}, which becomes current. */
    private boolean readForwardTo(String id) throws IOException {
        String header = pendingHeader != null ? pendingHeader : scanner.nextHeader(null);
        pendingHeader = null;
        while (header != null) {
            if (SequenceFileUtils.fastaId(header).equals(id)) {
                PackedBases bases = new PackedBases();
                pendingHeader = scanner.nextHeader(bases);
                currentId = id;
                current = new TrimmedEnds.Template(1, bases, bases.length());
                return true;
            }
            header = scanner.nextHeader(null);
        }
        return false;
    }

    private void restart() throws IOException {
        close();
        scanner = new Scanner(files);
        pendingHeader = null;
    }

    private Set<String> indexIds() throws IOException {
        Set<String> ids = new HashSet<>();
        try (Scanner all = new Scanner(files)) {
            String header;
            while ((header = all.nextHeader(null)) != null) {
                ids.add(SequenceFileUtils.fastaId(header));
            }
        }
        return ids;
    }

    /**
     * Reads FASTA files one after the other, a block of characters at a time. Lines are
     * never built: a contig written on a single line, hundreds of millions of characters
     * long, is read like a wrapped one.
     */
    private static final class Scanner implements AutoCloseable {
        private final List<Path> files;
        private int fileIndex = -1;
        private BufferedReader reader;
        private final char[] block = new char[1 << 16];
        private int filled;
        private int next;
        private boolean atLineStart = true;

        Scanner(List<Path> files) {
            this.files = files;
        }

        /**
         * Reads on to the next header line and returns it, {@code >} included, or
         * {@code null} after the last record. The sequence passed on the way is added to
         * {@code sequence} without its line ends and blanks, or skipped when it is
         * {@code null}.
         */
        String nextHeader(PackedBases sequence) throws IOException {
            int c;
            while ((c = read()) >= 0) {
                if (c == '\n' || c == '\r') {
                    atLineStart = true;
                } else if (atLineStart && c == '>') {
                    return headerLine();
                } else {
                    atLineStart = false;
                    if (sequence != null && !Character.isWhitespace(c)) {
                        sequence.append((char) c);
                    }
                }
            }
            return null;
        }

        /** The rest of a header line whose {@code >} was just read. */
        private String headerLine() throws IOException {
            StringBuilder header = new StringBuilder(">");
            int c;
            while ((c = read()) >= 0 && c != '\n' && c != '\r') {
                header.append((char) c);
            }
            atLineStart = true;
            return header.toString();
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

    @Override
    public void close() throws IOException {
        scanner.close();
    }
}
