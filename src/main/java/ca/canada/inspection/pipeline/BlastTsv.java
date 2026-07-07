package ca.canada.inspection.pipeline;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/** BLAST TSV constants and streaming-safe file helpers. */
final class BlastTsv {
    static final String OUTFMT = "6 qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq";
    static final String HEADER = String.join("\t", List.of(
            "qseqid", "sseqid", "positive", "mismatch", "gaps", "evalue",
            "bitscore", "slen", "length", "qstart", "qend", "qseq", "sstart", "send", "sseq"
    ));

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
