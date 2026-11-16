package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.BlastField;
import ca.canada.inspection.pcr.BlastHitParser;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.QueryTemplates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

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
     * the header: on a genome blastn lists 1.5 million alignments for a 40-assay panel (140 MB), of which a
     * few thousand are primer sites, and the table was kept whole, then copied again to add its header.
     *
     * @param searched the FASTA files blastn searched, in which a hit's trimmed ends are compared
     */
    static void keepAccepted(Path path, List<Path> searched, PrimerPanel primers, AmpliconOptions options) {
        keepAccepted(path, searched, primers, options, java.util.function.UnaryOperator.identity());
    }

    /**
     * As {@link #keepAccepted(Path, List, PrimerPanel, AmpliconOptions)}, each line first put back on the
     * sequence searched by {@code toContig} ({@link BlastQuery#toContig}, for a query searched in pieces),
     * which drops it ({@code null}) when the piece next to it owns the hit.
     */
    static void keepAccepted(Path path, List<Path> searched, PrimerPanel primers, AmpliconOptions options,
                             java.util.function.UnaryOperator<String> toContig) {
        Objects.requireNonNull(path, "path");
        // Written beside it with the folder's usual permissions, then moved over it: a temporary file
        // (Files.createTempFile) is readable by its owner only, and so was the hit file it became, to
        // colleagues sharing the output folder.
        Path temp = path.resolveSibling(path.getFileName() + ".part");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                // Read as the searched FASTA is (leniently): an ID's byte that is not UTF-8 reads the same in both.
                try (java.io.BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
                     QueryTemplates templates = new QueryTemplates(searched)) {
                    out.write(HEADER);
                    out.write('\n');
                    if (Files.exists(path)) {
                        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                                Files.newInputStream(path), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = in.readLine()) != null) {
                                if (line.isBlank() || (line = toContig.apply(line)) == null) {
                                    continue;
                                }
                                if (BlastHitParser.parse(line, primers, options, templates).isPresent()) {
                                    out.write(line);
                                    out.write('\n');
                                }
                            }
                        }
                    }
                }
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write the BLAST hits of " + path, e);
        }
    }
}
