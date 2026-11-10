package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.BlastHitParser;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
     * @param processes          tracks the blastn processes so a GUI can kill them; may be {@code null}
     * @param log                receives blastn output lines; {@code null} inherits the console
     * @param cancelled          checked before each sample; may be {@code null}
     */
    public record Settings(Path blastn, Path primerDatabase, double evalue, int threads, double minSupportFraction,
                           ExternalProcessTracker processes, Consumer<String> log, BooleanSupplier cancelled) {
        public Settings {
            ReadLevelCaller.requireFraction(minSupportFraction);
            if (threads < 1) {
                throw new IllegalArgumentException("threads must be >= 1");
            }
        }

        /** Command-line settings: blastn output goes to the console, nothing to cancel. */
        public Settings(Path blastn, Path primerDatabase, double evalue, int threads, double minSupportFraction) {
            this(blastn, primerDatabase, evalue, threads, minSupportFraction, null, null, null);
        }
    }

    /** The reads searched for one sample and which of them are mates of one pair. */
    record ReadSet(Map<String, Integer> lengths, Map<String, String> descriptions, Map<String, String> fragments) {
    }

    private ReadLevelStage() {
    }

    /** @return read-level evidence per FASTQ sample name */
    public static Map<String, ReadEvidence> run(Settings settings, Path detailedDir, Map<String, Sample> samples,
                                                Map<String, String> primers, AmpliconOptions options) {
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
        ParallelStageRunner.run("Read-level amplicon search", fastq, settings.threads(), sample -> {
            if (settings.cancelled() != null && settings.cancelled().getAsBoolean()) {
                throw new CancellationException("Read-level search was cancelled.");
            }
            evidence.put(sample.getName(), searchSample(settings, blastThreads,
                    detailedDir.resolve(sample.getName()), sample, primers, options));
        });
        return Map.copyOf(evidence);
    }

    private static ReadEvidence searchSample(Settings settings, int blastThreads, Path sampleDir, Sample sample,
                                             Map<String, String> primers, AmpliconOptions options) {
        String name = sample.getName();
        Path fasta = BbtoolsCommands.baitedReadsFasta(sampleDir, name);
        Path hitsFile = BbtoolsCommands.baitedReadsHits(sampleDir, name);
        boolean paired = sample.files().size() == 2;
        ReadSet reads = writeReadsFasta(readInputs(sampleDir, name, paired), paired, fasta);
        if (reads.lengths().isEmpty()) {
            deleteQuietly(hitsFile); // no stale hits from an earlier run in this folder
            return new ReadEvidence(Map.of(), Map.of(), Map.of());
        }
        runBlast(settings, blastThreads, fasta, hitsFile);
        BlastTsv.prependHeader(hitsFile);
        return callFromHits(name, hitsFile, fasta, reads, primers, options, settings.minSupportFraction());
    }

    /**
     * Calls amplicons from the primer hits on a sample's baited reads.
     *
     * @param hitsFile BLAST hits of the primers on the reads of {@code fasta}
     * @param fasta    the reads searched, as written by {@link #writeReadsFasta}
     */
    static ReadEvidence callFromHits(String name, Path hitsFile, Path fasta, ReadSet reads,
                                     Map<String, String> primers, AmpliconOptions options,
                                     double minSupportFraction) {
        Sample readSample = new Sample();
        readSample.setName(name);
        readSample.setFileType("reads");
        reads.descriptions().forEach(readSample::addContig);
        try (BufferedReader reader = Files.newBufferedReader(hitsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("qseqid")) {
                    continue;
                }
                BlastHitParser.parse(line, name, primers, options)
                        .ifPresent(hit -> readSample.addBlastResult(hit.subjectID(), hit));
            }
        } catch (IOException e) {
            throw new PipelineException("Could not parse read-level BLAST output: " + hitsFile, e);
        }
        return ReadLevelCaller.evaluate(readSample, reads.lengths(), reads.fragments(), primers, options,
                minSupportFraction, readIds -> readSequences(fasta, readIds));
    }

    /**
     * Sequences of the given reads from the FASTA written by {@link #writeReadsFasta}
     * (one sequence line per record). Read on demand, for the few reads that carry a
     * primer, rather than kept in memory for every baited read.
     */
    static Map<String, String> readSequences(Path fasta, Set<String> readIds) {
        Map<String, String> sequences = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(fasta, StandardCharsets.UTF_8)) {
            String header;
            while ((header = reader.readLine()) != null && sequences.size() < readIds.size()) {
                String sequence = reader.readLine();
                if (!header.startsWith(">") || sequence == null) {
                    throw new PipelineException("Malformed baited reads FASTA: " + fasta + " near: " + header);
                }
                int space = header.indexOf(' ');
                String id = header.substring(1, space < 0 ? header.length() : space);
                if (readIds.contains(id)) {
                    sequences.put(id, sequence);
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Could not read baited reads FASTA: " + fasta, e);
        }
        return sequences;
    }

    private static void runBlast(Settings settings, int blastThreads, Path fasta, Path hitsFile) {
        List<String> command = List.of(
                settings.blastn().toString(),
                "-task", "blastn-short",
                "-query", fasta.toString(),
                "-db", settings.primerDatabase().toString(),
                "-evalue", Double.toString(settings.evalue()),
                "-num_alignments", "1000000",
                "-num_threads", String.valueOf(blastThreads),
                "-outfmt", BlastTsv.OUTFMT,
                "-out", hitsFile.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        if (settings.log() == null) {
            builder.inheritIO();
        } else {
            builder.redirectErrorStream(true);
        }
        try {
            Process process = builder.start();
            if (settings.processes() != null) {
                settings.processes().add(process);
            }
            if (settings.log() != null) {
                try (BufferedReader output = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = output.readLine()) != null) {
                        settings.log().accept(line);
                    }
                }
            }
            int exit = process.waitFor();
            if (settings.cancelled() != null && settings.cancelled().getAsBoolean()) {
                throw new CancellationException("Read-level search was cancelled.");
            }
            if (exit != 0) {
                throw new PipelineException("blastn failed with exit code " + exit + ": " + String.join(" ", command));
            }
        } catch (IOException e) {
            throw new PipelineException("Could not run blastn: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("Interrupted while running blastn on " + fasta, e);
        }
    }

    /**
     * The reads to search: merged pairs plus unmerged mates for paired input (BBMerge
     * ran this run), the first-pass reads otherwise. Decided from the input, not from
     * which files exist: a reused output folder may hold an earlier run's merge output.
     */
    static List<Path> readInputs(Path sampleDir, String name, boolean paired) {
        if (paired) {
            return List.of(BbtoolsCommands.mergedReads(sampleDir, name), BbtoolsCommands.unmergedReads(sampleDir, name));
        }
        return List.of(BbtoolsCommands.targetMatches(sampleDir, name));
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
