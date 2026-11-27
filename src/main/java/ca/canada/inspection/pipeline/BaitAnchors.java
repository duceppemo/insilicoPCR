package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.Hits;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * The reads of the first baiting that hold a primer site the rules accept (searched with blastn, as
 * contigs are), with their mates: what the second baiting starts from. The first baiting matches
 * k-mers shorter than the primers, within an edit (in 1.x, within {@code -m} substitutions and indels),
 * and with permissive settings it catches reads of rRNA, mitochondria and repeats that share such a k-mer with a primer
 * and hold no site; a second baiting from every one of them took their whole families.
 */
final class BaitAnchors {

    private BaitAnchors() {
    }

    /**
     * Writes the anchors of a sample ({@link SampleFiles#baitAnchors()}) from its first baiting.
     *
     * @return how many reads of the first baiting hold an accepted primer site
     */
    static int write(PipelineContext context, SampleFiles files, boolean paired, int threads) {
        Path firstBait = files.targetMatches();
        // Unpaired, the first bait's reads are what the read-level search searches (ReadLevelStage#readInputs): they
        // are written and searched once, as it reads them, and their hits kept for it. Paired, it searches the
        // pairs BBMerge makes of them.
        Path query = paired ? files.baitAnchorsQuery() : files.baitedReadsFasta();
        Path hits = paired ? files.baitAnchorsHits() : files.baitedReadsHits();
        boolean kept = false;
        try {
            // The sample's own word: mates named apart (fastq-dump's SRR1.1.1 and SRR1.1.2) are not alike by name.
            int records = paired ? writeQuery(firstBait, query) : writeReads(firstBait, query);
            Hits accepted = new Hits();
            if (records > 0) {
                BlastTsv.runBlastn(context.monitor(), context.dependencies().blastn(), query,
                        context.directories().primerDatabase(), context.config().evalue(), threads, hits);
                BlastTsv.keepAccepted(hits, List.of(query), context.primers(), context.config().ampliconOptions(),
                        accepted);
            }
            // Read r of the first baiting is query r; a read with a site keeps its mate (paired reads come as
            // mates one after the other).
            BitSet keep = new BitSet(records);
            int[] withSite = {0};
            accepted.byOligo().values().forEach(list -> {
                for (BlastResult hit : list) {
                    // r<index>, or read_<index> as the read-level search names the reads
                    String id = hit.queryID();
                    int record = Integer.parseInt(id.substring(id.startsWith("read_") ? 5 : 1));
                    if (!keep.get(record)) {
                        withSite[0]++;
                    }
                    keep.set(record);
                }
            });
            if (paired) {
                BitSet mates = new BitSet(records);
                keep.stream().forEach(record -> mates.set(record ^ 1));
                keep.or(mates);
            }
            writeKept(firstBait, keep, files.baitAnchors());
            kept = !paired && records > 0;
            return withSite[0];
        } catch (IOException e) {
            throw new PipelineException("Could not find the primer sites of the baited reads: " + firstBait, e);
        } finally {
            if (!kept) {
                ca.canada.inspection.util.OutputFiles.deleteQuietly(query);
                ca.canada.inspection.util.OutputFiles.deleteQuietly(hits); // none from an earlier run in this folder
            }
        }
    }

    /**
     * Writes the reads of {@code fastq} as the read-level search writes them ({@link BaitedReads#write}, each
     * read {@code read_<index>}); @return how many.
     */
    private static int writeReads(Path fastq, Path query) throws IOException {
        if (!Files.isRegularFile(fastq) || Files.size(fastq) == 0) {
            Files.write(query, new byte[0]);
            return 0;
        }
        try {
            return BaitedReads.write(List.of(fastq), false, query).lengths().size();
        } catch (PipelineException e) {
            throw new IOException(e.getMessage(), e); // said as for a paired sample's reads
        }
    }

    /** Writes the reads of {@code fastq} as FASTA named {@code r<index>}; @return how many. */
    private static int writeQuery(Path fastq, Path query) throws IOException {
        int[] index = {0};
        try (BufferedWriter out = Files.newBufferedWriter(query, StandardCharsets.UTF_8)) {
            forEachRecord(fastq, record -> write(out, ">r" + index[0]++ + "\n" + record[1] + "\n"));
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        return index[0];
    }

    /** Copies the records of {@code fastq} whose index is in {@code keep} to {@code anchors}, gzipped. */
    private static void writeKept(Path fastq, BitSet keep, Path anchors) throws IOException {
        int[] index = {0};
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(anchors)), StandardCharsets.UTF_8))) {
            forEachRecord(fastq, record -> {
                if (keep.get(index[0]++)) {
                    write(out, String.join("\n", record) + "\n");
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Each record of the first bait's reads, none for a missing or empty file (no read baited). */
    private static void forEachRecord(Path fastq, java.util.function.Consumer<String[]> each) throws IOException {
        if (Files.isRegularFile(fastq) && Files.size(fastq) > 0) {
            ca.canada.inspection.util.FastqRecords.forEachRecordLines(fastq, each);
        }
    }

    private static void write(BufferedWriter out, String text) {
        try {
            out.write(text);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

}
