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
 * <p>On Windows, a file whose path is too long for BLAST+ is copied likewise
 * ({@link #of(Path, Path, boolean)}).</p>
 *
 * <p>{@code blastn} does not read gzipped FASTA: given a {@code .gz} file it stops with
 * "BLAST query error". A gzipped assembly is therefore decompressed to a file that lasts
 * as long as the search; any other file is searched where it is.</p>
 */
final class BlastQuery implements AutoCloseable {
    private static final String PREFIX = "blast_query_";
    /** The decompressed copies this class writes, as a file name pattern. */
    static final String LEFT_BEHIND = PREFIX + "*.fasta";
    /** The longest name such a copy can have ({@link Files#createTempFile}: the prefix, a number, the suffix). */
    static final String LONGEST_NAME = PREFIX + Long.toUnsignedString(-1L) + ".fasta";

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
        return of(fasta, scratchDirectory, WindowsPathLimit.tooLong(fasta));
    }

    /**
     * @param tooLong whether {@code blastn} cannot open {@code fasta} where it is: on Windows, BLAST+
     *                cannot open a path of 260 characters or more ("File is not accessible"), which Java
     *                reads and writes, so a FASTA file in a deep folder is searched from a copy
     */
    static BlastQuery of(Path fasta, Path scratchDirectory, boolean tooLong) throws IOException {
        boolean gzipped = fasta.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz");
        if (!gzipped && !tooLong) {
            return new BlastQuery(fasta, false);
        }
        Path plain = Files.createTempFile(scratchDirectory, PREFIX, ".fasta");
        try (InputStream in = gzipped ? new GZIPInputStream(Files.newInputStream(fasta)) : Files.newInputStream(fasta)) {
            Files.copy(in, plain, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(plain);
            throw new IOException("Unable to " + (gzipped ? "decompress " : "copy ") + fasta + ": " + e.getMessage(), e);
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
