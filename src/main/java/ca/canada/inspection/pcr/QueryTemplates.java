package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.util.FastaScanner;
import ca.canada.inspection.util.SequenceFileUtils;

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
    private FastaScanner scanner;
    /** The header the scanner stopped at after the current record: the next record to look at. */
    private String pendingHeader;
    private String currentId;
    private TrimmedEnds.Template current;
    /** Every record ID of the files; built only once a query is not found reading forward. */
    private Set<String> allIds;
    private final Set<String> missing = new HashSet<>();
    /** The circular sequences' lengths as circles: their templates go on past the origin. */
    private final java.util.Map<String, Integer> circles;

    public QueryTemplates(List<Path> fastaFiles) {
        this(fastaFiles, java.util.Map.of());
    }

    public QueryTemplates(List<Path> fastaFiles, java.util.Map<String, Integer> circles) {
        this.files = List.copyOf(fastaFiles);
        this.scanner = new FastaScanner(files);
        this.circles = java.util.Map.copyOf(circles);
    }

    @Override
    public TrimmedEnds.Template apply(BlastResult hit) {
        String id = hit.queryID();
        if (id.equals(currentId)) {
            return current;
        }
        // Known to be in no file: reading on to the end would only lose the place for the next query.
        if (missing.contains(id) || allIds != null && !allIds.contains(id)) {
            missing.add(id);
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
                pendingHeader = scanner.nextHeader(base -> bases.append((char) base));
                currentId = id;
                current = new TrimmedEnds.Template(bases, circles.getOrDefault(id, 0));
                return true;
            }
            header = scanner.nextHeader(null);
        }
        return false;
    }

    private void restart() throws IOException {
        close();
        scanner = new FastaScanner(files);
        pendingHeader = null;
    }

    private Set<String> indexIds() throws IOException {
        Set<String> ids = new HashSet<>();
        try (FastaScanner all = new FastaScanner(files)) {
            String header;
            while ((header = all.nextHeader(null)) != null) {
                ids.add(SequenceFileUtils.fastaId(header));
            }
        }
        return ids;
    }

    @Override
    public void close() throws IOException {
        scanner.close();
    }
}
