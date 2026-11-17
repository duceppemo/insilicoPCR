package ca.canada.inspection.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Reads the records of a FASTQ file the run wrote (gzipped or not, by its first bytes), in order: the
 * one reader of the reads the FASTQ-mode steps write and read back (the baited reads, the bait
 * anchors, the reads a round of the read extension recruits). Blank lines between records are
 * skipped; a record is four lines, the first starting with {@code @}.
 */
public final class FastqRecords {

    /** One record: its header without the {@code @}, trimmed, and its sequence. */
    public record Record(String header, String sequence) {
    }

    private FastqRecords() {
    }

    /**
     * Gives {@code each} every record of {@code fastq}.
     *
     * @throws UncheckedIOException when the file cannot be read, or holds a record that is
     *                              not four lines starting with {@code @}
     */
    public static void forEach(Path fastq, Consumer<Record> each) {
        forEachRecordLines(fastq, lines -> each.accept(new Record(lines[0].substring(1).trim(), lines[1].trim())));
    }

    /**
     * Gives {@code each} the four lines of every record of {@code fastq}, as written.
     *
     * @throws UncheckedIOException when the file cannot be read, or holds a record that is
     *                              not four lines starting with {@code @}
     */
    public static void forEachRecordLines(Path fastq, Consumer<String[]> each) {
        try (BufferedReader reader = SequenceFileUtils.openWritten(fastq)) {
            String header;
            while ((header = reader.readLine()) != null) {
                if (header.isBlank()) {
                    continue;
                }
                String[] record = {header, reader.readLine(), reader.readLine(), reader.readLine()};
                if (!header.startsWith("@") || record[3] == null) {
                    throw new UncheckedIOException("Malformed FASTQ record in " + fastq + " near: " + header,
                            new IOException("not four lines starting with @"));
                }
                each.accept(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + fastq, e);
        }
    }
}
