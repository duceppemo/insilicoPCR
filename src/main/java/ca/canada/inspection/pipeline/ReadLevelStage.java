package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Hits;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.BlastHits;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FASTQ mode: searches the first-pass baited reads directly for amplicons, so a
 * target the mini-assembly omitted or split (#7) can still be called when single
 * reads or merged pairs hold it. A step of {@link InSilicoPcrPipeline}, run after the
 * primer BLAST database exists.
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

    private ReadLevelStage() {
    }

    /** @return read-level evidence per FASTQ sample name */
    public static Map<String, ReadEvidence> run(Settings settings, PipelineDirectories directories,
                                                Map<String, Sample> samples,
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
                        directories.sample(sample.getName()), sample, primers, options)),
                ParallelStageRunner.Progress.NONE);
        return Map.copyOf(evidence);
    }

    private static ReadEvidence searchSample(Settings settings, int blastThreads, SampleFiles files, Sample sample,
                                             PrimerPanel primers, AmpliconOptions options) {
        String name = sample.getName();
        Path fasta = files.baitedReadsFasta();
        Path hitsFile = files.baitedReadsHits();
        boolean paired = sample.files().size() == 2;
        BaitedReads.ReadSet reads = BaitedReads.write(readInputs(files, paired), paired, fasta);
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
     * @param fasta    the reads searched, as written by {@link BaitedReads#write}
     */
    static ReadEvidence callFromHits(String name, Path hitsFile, Path fasta, BaitedReads.ReadSet reads,
                                     PrimerPanel primers, AmpliconOptions options,
                                     double minSupportFraction, Consumer<String> warning) {
        // The reads' hits, not a sample: the sample is the one of its own name, with its files.
        Hits readHits = new Hits();
        reads.descriptions().forEach(readHits::describe);
        String missing = BlastHits.missingWarning(
                BlastHits.read(hitsFile, List.of(fasta), readHits, primers, options), List.of(fasta));
        if (!missing.isEmpty()) {
            warning.accept(missing);
        }
        return ReadLevelCaller.evaluate(name, readHits, reads.lengths(), reads.fragments(), primers, options,
                minSupportFraction, readIds -> BaitedReads.sequences(fasta, readIds));
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

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover file is only clutter; it is never read without being rewritten.
        }
    }
}
