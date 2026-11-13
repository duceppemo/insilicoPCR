package ca.canada.inspection.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * The FASTA file given to {@code blastn} as its query.
 *
 * <p>{@code blastn} does not read gzipped FASTA: given a {@code .gz} file it stops with
 * "BLAST query error". A gzipped assembly is therefore decompressed to a file that lasts
 * as long as the search; any other file is searched where it is.</p>
 */
final class BlastQuery implements AutoCloseable {
    private final Path path;
    private final boolean temporary;

    private BlastQuery(Path path, boolean temporary) {
        this.path = path;
        this.temporary = temporary;
    }

    /**
     * The query for {@code fasta}: the file itself, or its decompressed copy in
     * {@code scratchDirectory} when its name ends with {@code .gz}.
     */
    static BlastQuery of(Path fasta, Path scratchDirectory) throws IOException {
        if (!fasta.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            return new BlastQuery(fasta, false);
        }
        Path plain = Files.createTempFile(scratchDirectory, "blast_query_", ".fasta");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(fasta))) {
            Files.copy(in, plain, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(plain);
            throw new IOException("Unable to decompress " + fasta + ": " + e.getMessage(), e);
        }
        return new BlastQuery(plain, true);
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
