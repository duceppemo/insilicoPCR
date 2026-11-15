package ca.canada.inspection.pipeline;

import ca.canada.inspection.util.FastqRecords;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The baited reads of a FASTQ sample as FASTA ({@link SampleFiles#baitedReadsFasta}): written
 * once by the read-level search, from the reads it searches, and read back by it and by the
 * read extension. The one place its format is written: each read named {@code read_<n>}, on
 * one sequence line.
 */
final class BaitedReads {

    /** The reads written, with their lengths and descriptions, and which of them are mates of one pair. */
    record ReadSet(Map<String, Integer> lengths, Map<String, String> descriptions, Map<String, String> fragments) {
    }

    /** One read of the file: its original header and its sequence. */
    record ReadRecord(String description, String sequence) {
    }

    private BaitedReads() {
    }

    /**
     * Writes FASTQ records as FASTA named {@code read_<n>}: SRA-style mates share a
     * name, and reads with the same BLAST query ID would be treated as one sequence.
     * The original header is kept as the description. For paired input the second
     * file holds BBMerge's unmerged mates, interleaved; consecutive records there are
     * one pair and share a fragment, so a read-level call counts the pair once.
     */
    static ReadSet write(List<Path> fastqFiles, boolean pairedUnmergedLast, Path fasta) {
        Map<String, Integer> lengths = new HashMap<>();
        Map<String, String> descriptions = new HashMap<>();
        Map<String, String> fragments = new HashMap<>();
        int[] index = {0};
        try (BufferedWriter writer = Files.newBufferedWriter(fasta, StandardCharsets.UTF_8)) {
            for (int file = 0; file < fastqFiles.size(); file++) {
                boolean interleavedMates = pairedUnmergedLast && file == fastqFiles.size() - 1;
                List<String> mateIds = new ArrayList<>(2);
                FastqRecords.forEach(fastqFiles.get(file), record -> {
                    String id = "read_" + index[0]++;
                    try {
                        writer.write(">" + id + " " + record.header());
                        writer.newLine();
                        writer.write(record.sequence());
                        writer.newLine();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    lengths.put(id, record.sequence().length());
                    descriptions.put(id, record.header());
                    if (interleavedMates) {
                        mateIds.add(id);
                        if (mateIds.size() == 2) {
                            fragments.put(mateIds.get(1), mateIds.get(0));
                            fragments.put(mateIds.get(0), mateIds.get(0));
                            mateIds.clear();
                        }
                    }
                });
            }
        } catch (IOException | UncheckedIOException e) {
            throw new PipelineException("Could not write baited reads FASTA: " + fasta + " ("
                    + (e.getMessage() == null ? e : e.getMessage()) + ")", e);
        }
        return new ReadSet(lengths, descriptions, fragments);
    }

    /**
     * The given reads, by read ID. Read on demand, for the few reads that carry a primer,
     * rather than kept in memory for every baited read.
     */
    static Map<String, ReadRecord> records(Path fasta, Set<String> readIds) {
        Map<String, ReadRecord> records = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(fasta, StandardCharsets.UTF_8)) {
            String header;
            while ((header = reader.readLine()) != null && records.size() < readIds.size()) {
                String sequence = reader.readLine();
                if (!header.startsWith(">") || sequence == null) {
                    throw new PipelineException("Malformed baited reads FASTA: " + fasta + " near: " + header);
                }
                String id = SequenceFileUtils.fastaId(header);
                if (readIds.contains(id)) {
                    records.put(id, new ReadRecord(SequenceFileUtils.fastaDescription(header), sequence));
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Could not read baited reads FASTA: " + fasta, e);
        }
        return records;
    }

    /** The sequences of the given reads, by read ID. */
    static Map<String, String> sequences(Path fasta, Set<String> readIds) {
        Map<String, String> sequences = new HashMap<>();
        records(fasta, readIds).forEach((id, record) -> sequences.put(id, record.sequence()));
        return sequences;
    }
}
