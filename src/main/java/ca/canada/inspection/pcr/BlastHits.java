package ca.canada.inspection.pcr;

import ca.canada.inspection.model.Hits;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Reads a BLAST TSV of the primers ({@code BlastTsv.OUTFMT}) into a sample: the one loop for the
 * contigs of a sample, the baited reads and the extended reads. A hit whose ends BLAST trimmed is
 * compared with the sequence it lies on ({@link TrimmedEnds}), read from the FASTA files BLAST
 * searched, alongside the report.
 */
public final class BlastHits {

    private BlastHits() {
    }

    /**
     * Adds the accepted hits of {@code tsv} to {@code into}.
     *
     * @param searched the FASTA files BLAST searched, in which trimmed hit ends are looked up
     * @return the sequences of trimmed hits not found in {@code searched}; their trimmed bases
     *         are not compared (see {@link #missingWarning})
     * @throws UncheckedIOException when the report or a searched file cannot be read
     */
    public static Set<String> read(Path tsv, List<Path> searched, Hits into, PrimerPanel primers,
                                   AmpliconOptions options) {
        // Decoded leniently, as the searched FASTA is (SequenceFileUtils.openMaybeGzip): a sequence ID with a
        // byte that is not UTF-8 reads the same way in both, instead of stopping the run here.
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(Files.newInputStream(tsv),
                StandardCharsets.UTF_8))) {
            return read(reader, searched, into, primers, options, java.util.function.UnaryOperator.identity(), line -> {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read BLAST report: " + tsv, e);
        }
    }

    /**
     * Adds the accepted hits of {@code lines}, blastn's table, to {@code into}, and gives each one's line,
     * put back on the sequence searched by {@code toContig} first, to {@code accepted}: the one loop that
     * judges hits, as a table is filtered and its hits taken in one pass.
     *
     * @param toContig a line as on the sequence searched, or {@code null} for a line to pass over (a query
     *                 searched in pieces: the hit of a piece that does not own it)
     * @return the sequences of trimmed hits not found in {@code searched} ({@link #missingWarning})
     */
    public static Set<String> read(BufferedReader lines, List<Path> searched, Hits into, PrimerPanel primers,
                                   AmpliconOptions options, java.util.function.UnaryOperator<String> toContig,
                                   java.util.function.Consumer<String> accepted) throws IOException {
        try (QueryTemplates templates = new QueryTemplates(searched, into.circles())) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank() || line.equals(BlastField.HEADER) || (line = toContig.apply(line)) == null) {
                    continue;
                }
                String kept = line;
                BlastHitParser.parse(line, primers, options, templates).ifPresent(hit -> {
                    into.add(hit.subjectID(), hit);
                    accepted.accept(kept);
                });
            }
            return templates.missing();
        }
    }

    /** The warning for {@link #read}'s missing sequences; empty when none is missing. */
    public static String missingWarning(Set<String> missing, List<Path> searched) {
        if (missing.isEmpty()) {
            return "";
        }
        return "Warning: " + missing.size() + " sequences with a trimmed primer or probe hit were not found in "
                + searched + " (e.g. " + java.util.Collections.min(missing)
                + "); the trimmed bases of their hits are not compared.";
    }
}
