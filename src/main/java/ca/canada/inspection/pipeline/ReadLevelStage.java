package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Hits;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.BlastHits;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FASTQ mode: searches the first-pass baited reads directly for amplicons, so a
 * target the mini-assembly omitted or split (#7) can still be called when single
 * reads or merged pairs hold it. Shared by the command-line and GUI pipelines; run
 * after the primer BLAST database exists.
 */
public final class ReadLevelStage {

    /**
     * @param blastn             blastn executable
     * @param primerDatabase     primer FASTA with its BLAST database
     * @param evalue             blastn e-value, as for contigs
     * @param threads            total threads; split between samples searched in parallel
     * @param minSupportFraction share of informative reads required for a read-level call
     * @param monitor            where program output goes, and whether the run was cancelled
     */
    public record Settings(Path blastn, Path primerDatabase, double evalue, int threads, double minSupportFraction,
                           RunMonitor monitor) {
        public Settings {
            ReadLevelCaller.requireFraction(minSupportFraction);
            if (threads < 1) {
                throw new IllegalArgumentException("threads must be >= 1");
            }
            Objects.requireNonNull(monitor, "monitor");
        }

        /** The settings of a run. */
        static Settings of(PipelineContext context) {
            return new Settings(context.dependencies().blastn(), context.directories().primerDatabase(),
                    context.config().evalue(), context.config().threads(), context.config().readMinFraction(),
                    context.monitor());
        }
    }

    /** The reads searched for one sample and which of them are mates of one pair. */
    record ReadSet(Map<String, Integer> lengths, Map<String, String> descriptions, Map<String, String> fragments) {
    }

    private ReadLevelStage() {
    }

    /** @return read-level evidence per FASTQ sample name */
    public static Map<String, ReadEvidence> run(Settings settings, Path detailedDir, Map<String, Sample> samples,
                                                PrimerPanel primers, AmpliconOptions options) {
        List<Sample> fastq = samples.values().stream()
                .filter(Sample::isFastq)
                .sorted(Comparator.comparing(Sample::getName))
                .toList();
        if (fastq.isEmpty()) {
            return Map.of();
        }
        // Samples run in parallel; give each blastn its share of the threads.
        int blastThreads = Math.max(1, settings.threads() / Math.min(settings.threads(), fastq.size()));
        Map<String, ReadEvidence> evidence = new ConcurrentHashMap<>();
        ParallelStageRunner.run("Read-level amplicon search", fastq, settings.threads(), settings.monitor(),
                sample -> evidence.put(sample.getName(), searchSample(settings, blastThreads,
                        SampleFiles.of(detailedDir, sample.getName()), sample, primers, options)),
                ParallelStageRunner.Progress.NONE);
        return Map.copyOf(evidence);
    }

    private static ReadEvidence searchSample(Settings settings, int blastThreads, SampleFiles files, Sample sample,
                                             PrimerPanel primers, AmpliconOptions options) {
        String name = sample.getName();
        Path fasta = files.baitedReadsFasta();
        Path hitsFile = files.baitedReadsHits();
        boolean paired = sample.files().size() == 2;
        ReadSet reads = writeReadsFasta(readInputs(files, paired), paired, fasta);
        if (reads.lengths().isEmpty()) {
            deleteQuietly(hitsFile); // no stale hits from an earlier run in this folder
            return new ReadEvidence(Map.of(), Map.of(), Map.of());
        }
        runBlast(settings, blastThreads, fasta, hitsFile);
        BlastTsv.prependHeader(hitsFile);
        return callFromHits(name, hitsFile, fasta, reads, primers, options, settings.minSupportFraction(),
                settings.monitor()::warning);
    }

    /**
     * Calls amplicons from the primer hits on a sample's baited reads.
     *
     * @param hitsFile BLAST hits of the primers on the reads of {@code fasta}
     * @param fasta    the reads searched, as written by {@link #writeReadsFasta}
     */
    static ReadEvidence callFromHits(String name, Path hitsFile, Path fasta, ReadSet reads,
                                     PrimerPanel primers, AmpliconOptions options,
                                     double minSupportFraction, Consumer<String> warning) {
        // The reads' hits, not a sample: the sample is the one of its own name, with its files.
        Hits readHits = new Hits();
        reads.descriptions().forEach(readHits::describe);
        String missing = BlastHits.missingWarning(
                BlastHits.read(hitsFile, List.of(fasta), name, readHits, primers, options), List.of(fasta));
        if (!missing.isEmpty()) {
            warning.accept(missing);
        }
        return ReadLevelCaller.evaluate(name, readHits, reads.lengths(), reads.fragments(), primers, options,
                minSupportFraction, readIds -> readSequences(fasta, readIds));
    }

    /**
     * Sequences of the given reads from the FASTA written by {@link #writeReadsFasta}
     * (one sequence line per record). Read on demand, for the few reads that carry a
     * primer, rather than kept in memory for every baited read.
     */
    static Map<String, String> readSequences(Path fasta, Set<String> readIds) {
        Map<String, String> sequences = new HashMap<>();
        readRecords(fasta, readIds).forEach((id, record) -> sequences.put(id, record.sequence()));
        return sequences;
    }

    /** One read of the FASTA written by {@link #writeReadsFasta}: its original header and its sequence. */
    record ReadRecord(String description, String sequence) {
    }

    /** The given reads from the FASTA written by {@link #writeReadsFasta}, by read ID. */
    static Map<String, ReadRecord> readRecords(Path fasta, Set<String> readIds) {
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

    /** Searches {@code fasta} for the primers with blastn, as contigs are searched. */
    static void runBlast(Settings settings, int blastThreads, Path fasta, Path hitsFile) {
        BlastTsv.runBlastn(settings.monitor(), settings.blastn(), fasta, settings.primerDatabase(), settings.evalue(),
                blastThreads, hitsFile);
    }

    /**
     * The reads to search: merged pairs plus unmerged mates for paired input (BBMerge
     * ran this run), the first-pass reads otherwise. Decided from the input, not from
     * which files exist: a reused output folder may hold an earlier run's merge output.
     */
    static List<Path> readInputs(SampleFiles files, boolean paired) {
        if (paired) {
            return List.of(files.mergedReads(), files.unmergedReads());
        }
        return List.of(files.targetMatches());
    }

    /**
     * Writes FASTQ records as FASTA named {@code read_<n>}: SRA-style mates share a
     * name, and reads with the same BLAST query ID would be treated as one sequence.
     * The original header is kept as the description. For paired input the second
     * file holds BBMerge's unmerged mates, interleaved; consecutive records there are
     * one pair and share a fragment, so a read-level call counts the pair once.
     */
    static ReadSet writeReadsFasta(List<Path> fastqFiles, boolean pairedUnmergedLast, Path fasta) {
        Map<String, Integer> lengths = new HashMap<>();
        Map<String, String> descriptions = new HashMap<>();
        Map<String, String> fragments = new HashMap<>();
        int index = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(fasta, StandardCharsets.UTF_8)) {
            for (int file = 0; file < fastqFiles.size(); file++) {
                Path fastq = fastqFiles.get(file);
                boolean interleavedMates = pairedUnmergedLast && file == fastqFiles.size() - 1;
                List<String> mateIds = new ArrayList<>(2);
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
                            throw new PipelineException("Malformed FASTQ record in " + fastq + " near: " + header);
                        }
                        String id = "read_" + index++;
                        String original = header.substring(1).trim();
                        writer.write(">" + id + " " + original);
                        writer.newLine();
                        writer.write(sequence.trim());
                        writer.newLine();
                        lengths.put(id, sequence.trim().length());
                        descriptions.put(id, original);
                        if (interleavedMates) {
                            mateIds.add(id);
                            if (mateIds.size() == 2) {
                                fragments.put(mateIds.get(1), mateIds.get(0));
                                fragments.put(mateIds.get(0), mateIds.get(0));
                                mateIds.clear();
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write baited reads FASTA: " + fasta, e);
        }
        return new ReadSet(lengths, descriptions, fragments);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover file is only clutter; it is never read without being rewritten.
        }
    }
}
