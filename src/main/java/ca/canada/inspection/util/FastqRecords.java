package ca.canada.inspection.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Reads the records of a FASTQ file (gzipped or not), in order: the one reader of the reads
 * the FASTQ-mode steps write and read back. Blank lines between records are skipped; a
 * record's sequence is trimmed.
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
        try (BufferedReader reader = SequenceFileUtils.openMaybeGzip(fastq)) {
            String header;
            while ((header = reader.readLine()) != null) {
                if (header.isBlank()) {
                    continue;
                }
                String sequence = reader.readLine();
                String plus = reader.readLine();
                String quality = reader.readLine();
                if (!header.startsWith("@") || sequence == null || plus == null || quality == null) {
                    throw new UncheckedIOException("Malformed FASTQ record in " + fastq + " near: " + header,
                            new IOException("not four lines starting with @"));
                }
                each.accept(new Record(header.substring(1).trim(), sequence.trim()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + fastq, e);
        }
    }
}
