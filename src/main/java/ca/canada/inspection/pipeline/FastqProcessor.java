package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.util.FastqRecords;
import ca.canada.inspection.util.InputCheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Baits, assembles and merges the reads of the FASTQ samples, several samples at a time.
 * The samples running at once share the threads and the memory; a sample that runs out
 * of memory is set aside and run again at the end, alone, with all of them.
 */
public final class FastqProcessor {

    /** The steps of a sample, in the order they run. */
    private enum ReadStep {
        FIRST_BAIT("First BBDuk baiting", "Completed First Baiting"),
        SECOND_BAIT("Second BBDuk baiting", "Completed Second Baiting"),
        ASSEMBLY("Tadpole assembly", null),
        MERGE("BBMerge of baited pairs", "Completed Assembly");

        private final String stageName;
        private final String completed;

        ReadStep(String stageName, String completed) {
            this.stageName = stageName;
            this.completed = completed;
        }

        boolean appliesTo(Sample sample) {
            return this != MERGE || sample.isPaired();
        }
    }

    private FastqProcessor() {
    }

    public static void process(PipelineContext context) {
        List<Sample> samples = fastqSamples(context);
        if (samples.isEmpty()) {
            return;
        }

        RunMonitor monitor = context.monitor();
        monitor.info("FastQ files identified, conducting baiting and assembly");
        FirstBait bait = firstBait(context);
        int budget = context.childJavaMemoryBudgetGiB();
        int threads = context.config().threads();
        ChildJavaMemory.Share alone = ChildJavaMemory.share(budget, threads, 1);

        // Samples that ran out of memory, by name, with the step they ran out of it in.
        Map<String, ReadStep> setAside = new ConcurrentHashMap<>();
        // Samples whose reads BBDuk could not read, by name, with its failure: left out of the run.
        Map<String, PipelineException> refused = new ConcurrentHashMap<>();
        ChildJavaMemory.Share previous = null;
        for (ReadStep step : ReadStep.values()) {
            List<Sample> stepSamples = samples.stream()
                    .filter(sample -> !setAside.containsKey(sample.getName()) && !refused.containsKey(sample.getName())
                            && step.appliesTo(sample))
                    .toList();
            // The samples of a step that run at once share the threads and the memory.
            ChildJavaMemory.Share share = ChildJavaMemory.share(budget, threads, stepSamples.size());
            if (!stepSamples.isEmpty() && !share.equals(previous)) {
                monitor.info(share.atOnce() == 1
                        ? "BBTools: one sample at a time, with " + describe(share)
                        : "BBTools: " + share.atOnce() + " samples at a time, each with " + describe(share));
                previous = share;
            }
            BbtoolsCommands commands = BbtoolsCommands.of(context.dependencies(), share.memoryGiB(), share.threads());
            BbtoolsCommands withSpare = BbtoolsCommands.of(context.dependencies(), share.memoryGiB(),
                    share.threads() + 1);
            // A child takes a spare thread while it runs, if one is free, and gives it back after.
            Semaphore spare = new Semaphore(share.spareThreads());
            // Only worth setting a sample aside if it gets more memory alone.
            boolean runAgainAlone = share.memoryGiB() < alone.memoryGiB();
            ParallelStageRunner.run(step.stageName, stepSamples, share.atOnce(), monitor, sample -> {
                boolean extra = spare.tryAcquire();
                try {
                    runStep(step, context, extra ? withSpare : commands, sample, bait);
                } catch (ProgramFailedException e) {
                    if (refusedTheReads(step == ReadStep.FIRST_BAIT, e)) {
                        refused.put(sample.getName(), e);
                        return;
                    }
                    if (!runAgainAlone || !BbtoolsCommands.ranOutOfMemory(e)) {
                        throw outOfMemoryOrAsIs(e, sample, share);
                    }
                    setAside.put(sample.getName(), step);
                    monitor.warning("Warning: " + e.program() + " ran out of memory on sample " + sample.getName()
                            + " with -Xmx" + share.memoryGiB() + "g; it will be run again after the other samples,"
                            + " alone, with -Xmx" + alone.memoryGiB() + "g");
                } catch (BbtoolsCommands.UnusablePath e) {
                    if (step != ReadStep.FIRST_BAIT) {
                        throw e;
                    }
                    refused.put(sample.getName(), e); // a read file BBTools cannot be given
                } finally {
                    if (extra) {
                        spare.release();
                    }
                }
            }, monitor::blastProgress); // the window's second bar: samples done in the step
            if (step.completed != null) {
                monitor.info(step.completed);
            }
        }
        leaveOut(context, refused, samples.size());

        List<Sample> again = samples.stream().filter(sample -> setAside.containsKey(sample.getName())).toList();
        if (again.isEmpty()) {
            return;
        }
        monitor.info("Running the samples that ran out of memory again, one at a time, with " + describe(alone)
                + ": " + String.join(", ", again.stream().map(Sample::getName).toList()));
        BbtoolsCommands whole = BbtoolsCommands.of(context.dependencies(), alone.memoryGiB(), alone.threads());
        for (Sample sample : again) {
            ReadStep from = setAside.get(sample.getName());
            for (ReadStep step : ReadStep.values()) {
                if (step.compareTo(from) >= 0 && step.appliesTo(sample)) {
                    try {
                        runStep(step, context, whole, sample, bait);
                    } catch (ProgramFailedException e) {
                        throw outOfMemoryOrAsIs(e, sample, alone);
                    }
                }
            }
            monitor.info("Completed sample " + sample.getName());
        }
    }

    /**
     * Whether {@code failure} is BBDuk refusing a sample's reads in the first bait, the first program to read
     * them ({@code firstBait}): a character the input check let through that BBDuk does not take, a file it reads otherwise
     * (a gzipped one named .GZ), a file truncated past the quick input check. It failed the whole run, the
     * other samples with it; as a FASTA file blastn refuses, the sample is left out. Out of memory is not
     * the file's fault (the sample is run again alone), nor a killed program (exit code 137 and up).
     */
    static boolean refusedTheReads(boolean firstBait, ProgramFailedException failure) {
        return firstBait && !BbtoolsCommands.ranOutOfMemory(failure) && failure.exitCode() == 1;
    }

    /** Leaves the samples of {@code refused} out of the run, named in warnings and in its input issues. */
    static void leaveOut(PipelineContext context, Map<String, PipelineException> refused, int readSets) {
        if (refused.isEmpty()) {
            return;
        }
        // Every read set BBDuk was given refused, or every sample: the fault is not the files' (a BBTools that cannot
        // run, an argument it rejects). Counted among the read sets: assemblies beside them ran, and a run of reads
        // that all went missing ended as if it had worked. A read set left out for its file's name (a comma, an
        // equals sign) is the file's fault, and BBDuk never saw it: two such sets failed the run, the assemblies
        // beside them with it.
        List<String> byBbduk = programAtFault(refused, readSets, context.samples().size());
        if (!byBbduk.isEmpty()) {
            throw new PipelineException("BBDuk could not read the reads of any sample (" + String.join(", ", byBbduk)
                    + ")", refused.get(byBbduk.getFirst()));
        }
        refused.keySet().stream().sorted().forEach(name -> {
            Sample sample = context.samples().remove(name);
            PipelineException why = refused.get(name);
            InputCheck.Issue issue = why instanceof BbtoolsCommands.UnusablePath
                    ? new InputCheck.Issue(name, fileNames(sample), "BBTools cannot be given the file", why.getMessage())
                    : new InputCheck.Issue(name, fileNames(sample), "BBDuk cannot read the reads",
                    "its message is in the log above: " + why.getMessage());
            context.inputIssues().add(issue);
            context.monitor().warning("Warning: " + issue.line());
        });
        if (context.samples().isEmpty()) {
            throw new PipelineException("No sample of " + context.config().input() + " can be analysed: every input"
                    + " file is faulty (each is named in a warning above)");
        }
    }

    private static List<String> fileNames(Sample sample) {
        return sample.files().stream().map(file -> file.getFileName().toString()).toList();
    }

    /**
     * The read sets BBDuk refused, sorted, when the fault is the program's rather than the files' (every read set it
     * was given refused, more than one or the run's only sample): empty otherwise. Read sets left out for their file's
     * name ({@link BbtoolsCommands.UnusablePath}) are the files' fault and do not count: BBDuk never saw them.
     *
     * @param readSets the run's read sets, samples the read steps took
     * @param samples  the run's samples, assemblies included
     */
    static List<String> programAtFault(Map<String, PipelineException> refused, int readSets, int samples) {
        List<String> byBbduk = refused.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof ProgramFailedException).map(Map.Entry::getKey).sorted()
                .toList();
        int named = refused.size() - byBbduk.size();
        int given = readSets - named;
        boolean all = !byBbduk.isEmpty() && byBbduk.size() == given && (given > 1 || samples - named == 1);
        return all ? byBbduk : List.of();
    }

    private static String describe(ChildJavaMemory.Share share) {
        String threads = share.spareThreads() == 0 ? share.threads() + " threads"
                : share.threads() + " or " + (share.threads() + 1) + " threads ("
                + (share.threads() * share.atOnce() + share.spareThreads()) + " in all)";
        return threads + " and -Xmx" + share.memoryGiB() + "g";
    }

    /** A failure that the user can do something about when it is a lack of memory. */
    private static PipelineException outOfMemoryOrAsIs(ProgramFailedException e, Sample sample,
                                                       ChildJavaMemory.Share share) {
        if (!BbtoolsCommands.ranOutOfMemory(e)) {
            return e;
        }
        return new PipelineException(BbtoolsCommands.outOfMemoryMessage(e.program(), " on sample " + sample.getName(),
                share.memoryGiB()), e);
    }

    /**
     * How the first bait baits: its passes, each a reference of oligos and a k ({@link BbtoolsCommands#baitGroups}), and
     * the oligos it baits with again for substitutions alone ({@link BbtoolsCommands#substitutionBait}), or
     * {@code null} for no such pass.
     */
    private record FirstBait(List<Pass> passes, Path substitutionReference) {
    }

    /**
     * The oligos the substitution bait baits with ({@link BbtoolsCommands#substitutionBait}), or {@code null} for no such
     * pass: those at least as long as its k and at most {@link PipelineConfig#TM_BAIT_SUBSTITUTION_MAX_LENGTH} bases,
     * written beside the primer database ({@code primer_tmp.fasta.s<substitutions>}).
     */
    private static Path substitutionReference(Map<String, String> oligos, Path database) {
        int substitutions = PipelineConfig.TM_BAIT_SUBSTITUTIONS;
        int k = BbtoolsCommands.substitutionBaitK(substitutions);
        int maxLength = PipelineConfig.TM_BAIT_SUBSTITUTION_MAX_LENGTH;
        StringBuilder fasta = new StringBuilder();
        int taken = 0;
        for (Map.Entry<String, String> oligo : oligos.entrySet()) {
            int length = oligo.getValue().length();
            if (length >= k && length <= maxLength) {
                fasta.append('>').append(oligo.getKey()).append('\n').append(oligo.getValue()).append('\n');
                taken++;
            }
        }
        if (taken == 0) {
            return null;
        }
        Path reference = database.resolveSibling(database.getFileName() + ".s" + substitutions);
        try {
            Files.writeString(reference, fasta.toString());
        } catch (IOException e) {
            throw new PipelineException("Could not write the oligos of the substitution bait: " + reference, e);
        }
        return reference;
    }

    /** A pass of the first bait: the oligos' FASTA file, and the k. */
    private record Pass(Path reference, int k) {
    }

    /**
     * The first bait of the run: one pass with the primer database when one k serves every oligo, else a pass per
     * group, each with its oligos written beside the database ({@code primer_tmp.fasta.k<k>}).
     */
    private static FirstBait firstBait(PipelineContext context) {
        Map<String, String> oligos = context.primers().sequences();
        if (oligos.isEmpty()) {
            throw new PipelineException("Primer dictionary is empty");
        }
        List<BbtoolsCommands.BaitGroup> groups = BbtoolsCommands.baitGroups(oligos, AmpliconOptions.FIVE_PRIME_FREE,
                PipelineConfig.TM_BAIT_MISMATCHES);
        Path database = context.directories().primerDatabase();
        Path substitutions = substitutionReference(oligos, database);
        if (groups.size() == 1) {
            return new FirstBait(List.of(new Pass(database, groups.getFirst().k())), substitutions);
        }
        List<Pass> passes = new ArrayList<>();
        List<String> described = new ArrayList<>();
        for (BbtoolsCommands.BaitGroup group : groups) {
            Path reference = database.resolveSibling(database.getFileName() + ".k" + group.k());
            StringBuilder fasta = new StringBuilder();
            IntSummaryStatistics lengths = new IntSummaryStatistics();
            for (String name : group.oligos()) {
                fasta.append('>').append(name).append('\n').append(oligos.get(name)).append('\n');
                lengths.accept(oligos.get(name).length());
            }
            try {
                Files.writeString(reference, fasta.toString());
            } catch (IOException e) {
                throw new PipelineException("Could not write the oligos of a bait pass: " + reference, e);
            }
            passes.add(new Pass(reference, group.k()));
            described.add("oligos of " + (lengths.getMin() == lengths.getMax() ? lengths.getMin() + " bases"
                    : lengths.getMin() + " to " + lengths.getMax() + " bases") + " with k=" + group.k());
        }
        context.monitor().info("First baiting in " + passes.size() + " passes, so that short oligos do not shorten the"
                + " k-mers of the others: " + String.join("; ", described));
        return new FirstBait(List.copyOf(passes), substitutions);
    }

    private static void runStep(ReadStep step, PipelineContext context, BbtoolsCommands commands, Sample sample,
                                FirstBait bait) {
        switch (step) {
            case FIRST_BAIT -> bait(context, commands, sample, bait);
            case SECOND_BAIT -> secondBait(context, commands, sample, commands.threads());
            case ASSEMBLY -> assemble(context, commands, sample);
            case MERGE -> merge(context, commands, sample);
        }
    }

    private static void merge(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        SampleFiles files = context.directories().sample(sample.getName());
        run(context, commands.mergePairs(files.targetMatches(),
                files.mergedReads(),
                files.unmergedReads()));
    }

    private static List<Sample> fastqSamples(PipelineContext context) {
        return PipelineContext.fastqSamples(context.samples());
    }

    private static void bait(PipelineContext context, BbtoolsCommands commands, Sample sample, FirstBait bait) {
        context.directories().createSampleDirectory(sample.getName());
        SampleFiles files = context.directories().sample(sample.getName());
        Pass first = bait.passes().getFirst();
        run(context, commands.firstBait(first.reference(), sample.files(), files.targetMatches(), first.k(),
                PipelineConfig.TM_BAIT_MISMATCHES));
        for (Pass more : bait.passes().subList(1, bait.passes().size())) {
            run(context, commands.firstBait(more.reference(), sample.files(), files.moreMatches(), more.k(),
                    PipelineConfig.TM_BAIT_MISMATCHES));
            addMore(files);
        }
        if (bait.substitutionReference() != null) {
            baitSubstitutions(context, commands, sample, files, bait.substitutionReference());
        }
    }

    /** Adds the reads of {@link SampleFiles#moreMatches} not in the first bait's yet, each pair once; removes the file. */
    private static int addMore(SampleFiles files) {
        int added = ReadPool.of(files.targetMatches()).addNew(files.moreMatches(), files.targetMatches());
        try {
            Files.deleteIfExists(files.moreMatches());
        } catch (IOException e) {
            throw new PipelineException("Could not remove " + files.moreMatches(), e);
        }
        return added;
    }

    /**
     * The reads within {@link PipelineConfig#TM_BAIT_SUBSTITUTIONS} substitutions of an 18-base k-mer of an oligo of 18
     * to 21 bases that the first bait missed, added to its reads, each pair once (since 2.0).
     */
    private static void baitSubstitutions(PipelineContext context, BbtoolsCommands commands, Sample sample,
                                          SampleFiles files, Path reference) {
        run(context, commands.substitutionBait(
                reference,
                sample.files(),
                files.moreMatches(),
                PipelineConfig.TM_BAIT_SUBSTITUTIONS
        ));
        int added = addMore(files);
        context.monitor().info("Sample " + sample.getName() + ": " + added + " read(s) within "
                + PipelineConfig.TM_BAIT_SUBSTITUTIONS
                + " substitutions of an oligo added to the first bait's");
    }

    /**
     * Second pass: the reads overlapping those of the first pass in which a primer site is accepted
     * ({@link BaitAnchors}), not every read of the first pass. With -m 3 and a short k-mer the first pass
     * holds reads of rRNA, mitochondria and repeats that share a k-mer with a primer and no site, and the
     * second pass took their whole families: a third of a 15-million-pair library, 17 minutes and 19 GB.
     */
    private static void secondBait(PipelineContext context, BbtoolsCommands commands, Sample sample, int threads) {
        SampleFiles files = context.directories().sample(sample.getName());
        int anchors = BaitAnchors.write(context, files, sample.isPaired(), threads);
        context.monitor().info("Sample " + sample.getName() + ": " + anchors + " first-bait read(s) hold an accepted"
                + " primer site");
        // Their adapter read-through and poly-G tails cut off first: they baited every adapter dimer and poly-G
        // read of a library (72% of SRR35797377's reads), all of it then assembled, for the same report.
        Path bait = files.baitAnchors();
        if (anchors > 0) {
            run(context, commands.trimAdapters(files.baitAnchors(), sample.isPaired(), files.baitAnchorsTrimmed()));
            bait = files.baitAnchorsTrimmed();
        }
        run(context, commands.secondBait(
                bait,
                sample.files(),
                files.doubleTargetMatches()
        ));
    }

    private static void assemble(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        SampleFiles files = context.directories().sample(sample.getName());
        Path output = files.assembly();
        int k = context.config().tadpole().k();
        run(context, commands.assemble(files.doubleTargetMatches(), output, context.config().tadpole()));
        String warning = longerThanTheReads(k, output, files.doubleTargetMatches(), sample.getName());
        if (warning != null) {
            context.monitor().warning(warning);
        }
    }

    /**
     * The warning that {@code --tadpole-k} is longer than every read Tadpole was given, when its assembly is empty
     * for it: --tadpole-k 200 on 150-base reads built nothing, without a word ("Query is Empty" from blastn). Null
     * otherwise, an empty assembly of a sample whose reads hold no product among them.
     */
    static String longerThanTheReads(int k, Path assembly, Path reads, String sample) {
        try {
            if (!Files.isRegularFile(assembly) || Files.size(assembly) > 0 || !Files.isRegularFile(reads)) {
                return null;
            }
            int[] longest = {0};
            FastqRecords.forEach(reads, record -> longest[0] = Math.max(longest[0], record.sequence().length()));
            return longest[0] > 0 && k > longest[0] ? "Warning: --tadpole-k " + k + " is longer than the reads of"
                    + " sample " + sample + " (" + longest[0] + " bases at most): its mini-assembly is empty, and its"
                    + " products are looked for in the reads only" : null;
        } catch (IOException | java.io.UncheckedIOException e) {
            return null; // the steps after it read these files and say what is wrong with them
        }
    }

    private static void run(PipelineContext context, BbtoolsCommands.Command command) {
        command.run(context.monitor());
    }
}
