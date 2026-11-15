package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.BlastField;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/** The BLAST TSV of a run: the search that writes it, its header, and streaming-safe file helpers. */
final class BlastTsv {
    static final String OUTFMT = BlastField.OUTFMT;
    static final String HEADER = BlastField.HEADER;

    /**
     * The {@code blastn} command searching {@code query} for the primers of {@code database},
     * writing their hits to {@code out} in this TSV ({@link BlastField}); the one search of a
     * run, for contigs, reads and extended reads alike.
     */
    static List<String> blastn(Path blastn, Path query, Path database, double evalue, int threads, Path out) {
        return List.of(blastn.toString(),
                "-task", "blastn-short",
                "-query", query.toString(),
                "-db", database.toString(),
                "-evalue", Double.toString(evalue),
                "-num_alignments", "1000000",
                "-num_threads", String.valueOf(threads),
                "-outfmt", OUTFMT,
                "-out", out.toString());
    }

    private BlastTsv() {
    }

    static void prependHeader(Path path) {
        Objects.requireNonNull(path, "path");

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path temp = (parent != null)
                    ? Files.createTempFile(parent, path.getFileName().toString(), ".tmp")
                    : Files.createTempFile(path.getFileName().toString(), ".tmp");

            try {
                try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    writer.write(HEADER);
                    writer.newLine();

                    if (Files.exists(path)) {
                        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                writer.write(line);
                                writer.newLine();
                            }
                        }
                    }
                }

                Files.move(
                        temp,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);

            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }

        } catch (IOException e) {
            throw new PipelineException("Could not add BLAST TSV header to: " + path, e);
        }
    }
}
