package ca.canada.inspection.pipeline;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * The reads the read extension extends through, each once: Tadpole counts a k-mer once per read
 * that holds it, and a read given twice (baited again by a later round, or already among the
 * baited reads) made a k-mer of one read count as two, past {@code mincountextend=2}: the extension
 * went on through stretches only one read covered, its errors and chimeras with it.
 *
 * <p>Reads are known by their name (up to the first blank, a trailing {@code /1} or {@code /2} left
 * out, so the two mates of a pair are one name and travel together), as a 64-bit hash: millions of
 * baited reads take 8 bytes each.</p>
 */
final class ReadPool {
    private final long[] baited;
    private final Set<Long> added = new HashSet<>();

    private ReadPool(long[] baited) {
        this.baited = baited;
    }

    /** The pool of the reads of {@code baitedReads}, a gzipped or plain FASTQ file. */
    static ReadPool of(Path baitedReads) {
        long[][] names = {new long[1024]};
        int[] count = {0};
        forEachRecord(baitedReads, record -> {
            if (count[0] == names[0].length) {
                names[0] = Arrays.copyOf(names[0], names[0].length * 2);
            }
            names[0][count[0]++] = key(record[0]);
        });
        long[] sorted = Arrays.copyOf(names[0], count[0]);
        Arrays.sort(sorted);
        return new ReadPool(sorted);
    }

    /**
     * Appends to {@code into} (gzipped FASTQ, created if need be) the reads of {@code recruited} not in
     * the pool yet, and adds them to it.
     *
     * @return how many reads were added
     */
    int addNew(Path recruited, Path into) {
        Set<Long> thisRound = new HashSet<>();
        int[] written = {0};
        // Compressed fast: the reads are Tadpole's input, read once a round, and the default level took up to 9 s
        // a round for a file it shrank little more.
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(
                into, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            {
                def.setLevel(java.util.zip.Deflater.BEST_SPEED);
            }
        }, StandardCharsets.UTF_8))) {
            forEachRecord(recruited, record -> {
                long name = key(record[0]);
                // Both mates of a new pair are written: the second finds the name this round added, not before.
                if (contains(name) && !thisRound.contains(name)) {
                    return;
                }
                thisRound.add(name);
                try {
                    for (String line : record) {
                        out.write(line);
                        out.write('\n');
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                written[0]++;
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not keep the reads baited from the extended reads: " + into, e);
        }
        added.addAll(thisRound);
        return written[0];
    }

    boolean contains(long name) {
        return Arrays.binarySearch(baited, name) >= 0 || added.contains(name);
    }

    /** The name of a read from its header line: up to the first blank, without @ and a /1 or /2 mate suffix. */
    static long key(String header) {
        int start = header.startsWith("@") ? 1 : 0;
        int end = start;
        while (end < header.length() && !Character.isWhitespace(header.charAt(end))) {
            end++;
        }
        if (end - start > 2 && header.charAt(end - 2) == '/' && (header.charAt(end - 1) == '1'
                || header.charAt(end - 1) == '2')) {
            end -= 2;
        }
        long hash = 0xcbf29ce484222325L; // FNV-1a, 64 bits
        for (int i = start; i < end; i++) {
            hash ^= header.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** Each four-line record of {@code fastq} (gzipped or not), as its lines; none for a missing or empty file. */
    private static void forEachRecord(Path fastq, java.util.function.Consumer<String[]> each) {
        try {
            if (!Files.isRegularFile(fastq) || Files.size(fastq) == 0) {
                return; // no read baited
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + fastq, e);
        }
        try {
            ca.canada.inspection.util.FastqRecords.forEachRecordLines(fastq, each);
        } catch (UncheckedIOException e) {
            throw new UncheckedIOException("Unable to read " + fastq + ": " + e.getMessage(), e.getCause());
        }
    }
}
