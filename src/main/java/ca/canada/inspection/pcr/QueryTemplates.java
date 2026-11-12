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
 * is held in memory, however large the assembly and however many hits it has. A
 * query asked for out of order is found by reading the files again from the start;
 * a query that is in none of them (its ID spelled differently by BLAST, say) gets no
 * template, and is looked for only once.</p>
 */
public final class QueryTemplates implements Function<BlastResult, TrimmedEnds.Template>, AutoCloseable {

    private final List<Path> files;
    private int fileIndex = -1;
    private BufferedReader reader;
    private String pendingHeader;
    private String currentId;
    private TrimmedEnds.Template current;
    /** Every record ID of the files; built only once a query is not found reading forward. */
    private Set<String> allIds;
    private final Set<String> missing = new HashSet<>();

    public QueryTemplates(List<Path> fastaFiles) {
        this.files = List.copyOf(fastaFiles);
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
        String header;
        while ((header = nextHeader()) != null) {
            String recordId = SequenceFileUtils.fastaId(header);
            if (recordId.equals(id)) {
                StringBuilder bases = new StringBuilder();
                String line;
                while ((line = nextLine()) != null && !line.startsWith(">")) {
                    bases.append(line.strip());
                }
                pendingHeader = line;
                currentId = id;
                current = TrimmedEnds.Template.whole(bases.toString());
                return true;
            }
        }
        return false;
    }

    /** The next header line, skipping the sequence lines of the record before it. */
    private String nextHeader() throws IOException {
        if (pendingHeader != null) {
            String header = pendingHeader;
            pendingHeader = null;
            return header;
        }
        String line;
        while ((line = nextLine()) != null) {
            if (line.startsWith(">")) {
                return line;
            }
        }
        return null;
    }

    /** The next line across the files, or {@code null} after the last one. */
    private String nextLine() throws IOException {
        while (true) {
            if (reader != null) {
                String line = reader.readLine();
                if (line != null) {
                    return line;
                }
                reader.close();
                reader = null;
            }
            if (fileIndex + 1 >= files.size()) {
                return null;
            }
            fileIndex++;
            reader = SequenceFileUtils.openMaybeGzip(files.get(fileIndex));
        }
    }

    private void restart() throws IOException {
        close();
        fileIndex = -1;
        pendingHeader = null;
    }

    private Set<String> indexIds() throws IOException {
        Set<String> ids = new HashSet<>();
        for (Path file : files) {
            try (BufferedReader in = SequenceFileUtils.openMaybeGzip(file)) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith(">")) {
                        ids.add(SequenceFileUtils.fastaId(line));
                    }
                }
            }
        }
        return ids;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }
}
