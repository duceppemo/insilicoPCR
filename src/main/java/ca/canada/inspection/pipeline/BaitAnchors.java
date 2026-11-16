package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.Hits;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The reads of the first baiting that hold a primer site the rules accept (searched with blastn, as
 * contigs are), with their mates: what the second baiting starts from. The first baiting matches
 * k-mers shorter than the primers, within {@code -m} substitutions and indels, and with permissive
 * settings it catches reads of rRNA, mitochondria and repeats that share such a k-mer with a primer
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
        Path query = files.baitAnchorsQuery();
        Path hits = files.baitAnchorsHits();
        try {
            // The sample's own word: mates named apart (fastq-dump's SRR1.1.1 and SRR1.1.2) are not alike by name.
            int records = writeQuery(firstBait, query);
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
                    int record = Integer.parseInt(hit.queryID().substring(1));
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
            return withSite[0];
        } catch (IOException e) {
            throw new PipelineException("Could not find the primer sites of the baited reads: " + firstBait, e);
        } finally {
            deleteQuietly(query);
            deleteQuietly(hits);
        }
    }

    /** Writes the reads of {@code fastq} as FASTA named {@code r<index>}; @return how many. */
    private static int writeQuery(Path fastq, Path query) throws IOException {
        int index = 0;
        try (BufferedReader in = open(fastq);
             BufferedWriter out = Files.newBufferedWriter(query, StandardCharsets.UTF_8)) {
            String header;
            while ((header = in.readLine()) != null) {
                if (header.isBlank()) {
                    continue;
                }
                String sequence = in.readLine();
                in.readLine();
                in.readLine();
                out.write(">r" + index++ + "\n" + (sequence == null ? "" : sequence) + "\n");
            }
        }
        return index;
    }

    /** Copies the records of {@code fastq} whose index is in {@code keep} to {@code anchors}, gzipped. */
    private static void writeKept(Path fastq, BitSet keep, Path anchors) throws IOException {
        int index = 0;
        try (BufferedReader in = open(fastq);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                     Files.newOutputStream(anchors)), StandardCharsets.UTF_8))) {
            String header;
            while ((header = in.readLine()) != null) {
                if (header.isBlank()) {
                    continue;
                }
                String[] record = {header, in.readLine(), in.readLine(), in.readLine()};
                if (keep.get(index++)) {
                    for (String line : record) {
                        out.write(line == null ? "" : line);
                        out.write('\n');
                    }
                }
            }
        }
    }

    private static BufferedReader open(Path fastq) throws IOException {
        if (!Files.isRegularFile(fastq) || Files.size(fastq) == 0) {
            return new BufferedReader(new java.io.StringReader(""));
        }
        InputStream raw = Files.newInputStream(fastq);
        boolean gzip;
        try (InputStream peek = Files.newInputStream(fastq)) {
            gzip = peek.read() == 0x1f && peek.read() == 0x8b;
        }
        return new BufferedReader(new InputStreamReader(gzip ? new GZIPInputStream(raw) : raw, StandardCharsets.UTF_8));
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a work file left behind
        }
    }
}
