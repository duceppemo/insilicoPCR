package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.BlastField;
import ca.canada.inspection.model.Hits;
import ca.canada.inspection.pcr.BlastHits;
import ca.canada.inspection.pcr.PrimerPanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The BLAST TSV of a run: the search that writes it, and the table it is kept as (accepted hits, under a header). */
final class BlastTsv {
    static final String OUTFMT = BlastField.OUTFMT;
    static final String HEADER = BlastField.HEADER;

    /**
     * Searches {@code query} for the primers of {@code database} with {@code blastn}, writing their
     * hits to {@code out} in this TSV ({@link BlastField}): the one search of a run, for contigs,
     * reads and extended reads alike.
     *
     * <p>BLAST+ splits a database path at its spaces itself (an output folder such as
     * {@code C:\Users\Jane Doe\run} would fail), so blastn runs in the database's folder and is
     * given its bare name.</p>
     */
    static void runBlastn(RunMonitor monitor, Path blastn, Path query, Path database, double evalue, int threads,
                          Path out) {
        // A report of the last run open in Excel: blastn said only "File is not accessible".
        try {
            Files.deleteIfExists(out);
        } catch (IOException e) {
            throw new PipelineException("Could not replace " + out + ", written by the last run into this folder: "
                    + OpenElsewhere.describe(e), e);
        }
        monitor.runProgram(database.toAbsolutePath().getParent(),
                blastn(blastn, query.toAbsolutePath(), database.getFileName().toString(), evalue, threads,
                        out.toAbsolutePath()), "blastn");
    }

    static List<String> blastn(Path blastn, Path query, String database, double evalue, int threads, Path out) {
        return List.of(blastn.toString(),
                "-task", "blastn-short",
                "-query", query.toString(),
                "-db", database,
                "-evalue", Double.toString(evalue),
                "-num_alignments", "1000000",
                "-num_threads", String.valueOf(threads),
                "-outfmt", OUTFMT,
                "-out", out.toString());
    }

    private BlastTsv() {
    }

    /**
     * Rewrites {@code path}, blastn's table, as the hits the rules accept ({@link BlastHitParser}), under
     * the header, and adds them to {@code into}: on a genome blastn lists 1.5 million alignments for a
     * 40-assay panel (140 MB), of which a few thousand are primer sites, and the table was kept whole,
     * then copied again to add its header, then read again for its hits.
     *
     * @param searched the FASTA files blastn searched, in which a hit's trimmed ends are compared
     * @param toContig each line as on the sequence searched ({@link BlastQuery#toContig}, for a query searched
     *                 in pieces), or {@code null} when the piece next to it owns the hit
     * @return the sequences of trimmed hits not found in {@code searched} ({@link BlastHits#missingWarning})
     */
    static Set<String> keepAccepted(Path path, List<Path> searched, PrimerPanel primers, AmpliconOptions options,
                                    java.util.function.UnaryOperator<String> toContig, Hits into) {
        // An assembly's: its primer hits cut by a contig end are kept too, for the split warning.
        return keepAccepted(path, searched, primers, options, toContig, into, true);
    }

    private static Set<String> keepAccepted(Path path, List<Path> searched, PrimerPanel primers,
                                            AmpliconOptions options, java.util.function.UnaryOperator<String> toContig,
                                            Hits into, boolean keepCutByEnd) {
        Objects.requireNonNull(path, "path");
        // Written beside it with the folder's usual permissions, then moved over it: a temporary file
        // (Files.createTempFile) is readable by its owner only, and so was the hit file it became, to
        // colleagues sharing the output folder.
        Path temp = ca.canada.inspection.util.OutputFiles.partOf(path);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                Set<String> missing = Set.of();
                // Read as the searched FASTA is (leniently): an ID's byte that is not UTF-8 reads the same in both.
                try (java.io.BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    out.write(HEADER);
                    out.write('\n');
                    if (Files.exists(path)) {
                        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                                Files.newInputStream(path), StandardCharsets.UTF_8))) {
                            missing = BlastHits.read(in, searched, into, primers, options, toContig, line -> {
                                try {
                                    out.write(line);
                                    out.write('\n');
                                } catch (IOException e) {
                                    throw new WriteFailed(e);
                                }
                            }, keepCutByEnd);
                        }
                    }
                } catch (WriteFailed e) {
                    throw e.cause;
                }
                ca.canada.inspection.util.OutputFiles.moveOver(temp, path);
                return missing;
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write the BLAST hits of " + path, e);
        }
    }

    /** A line of the filtered table that could not be written: the table's failure, not the search's. */
    private static final class WriteFailed extends RuntimeException {
        private final IOException cause;

        WriteFailed(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    /** As {@link #keepAccepted(Path, List, PrimerPanel, AmpliconOptions, java.util.function.UnaryOperator, Hits)}, for a query searched whole. */
    static Set<String> keepAccepted(Path path, List<Path> searched, PrimerPanel primers, AmpliconOptions options,
                                    Hits into) {
        // Reads' (each read ends somewhere): no hit cut by an end is kept.
        return keepAccepted(path, searched, primers, options, java.util.function.UnaryOperator.identity(), into, false);
    }
}
