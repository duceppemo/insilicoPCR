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
                StandardCharsets.UTF_8));
             QueryTemplates templates = new QueryTemplates(searched)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.equals(BlastField.HEADER)) {
                    continue;
                }
                BlastHitParser.parse(line, primers, options, templates)
                        .ifPresent(hit -> into.add(hit.subjectID(), hit));
            }
            return templates.missing();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read BLAST report: " + tsv, e);
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
