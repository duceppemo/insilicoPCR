package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;

import java.nio.file.Path;
import java.util.Comparator;
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
            return this != MERGE || sample.files().size() == 2;
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
        int kLength = context.primers().sequences().values().stream().mapToInt(String::length).min()
                .orElseThrow(() -> new PipelineException("Primer dictionary is empty"));
        int budget = context.childJavaMemoryBudgetGiB();
        int threads = context.config().threads();
        ChildJavaMemory.Share alone = ChildJavaMemory.share(budget, threads, 1);

        // Samples that ran out of memory, by name, with the step they ran out of it in.
        Map<String, ReadStep> setAside = new ConcurrentHashMap<>();
        ChildJavaMemory.Share previous = null;
        for (ReadStep step : ReadStep.values()) {
            List<Sample> stepSamples = samples.stream()
                    .filter(sample -> !setAside.containsKey(sample.getName()) && step.appliesTo(sample))
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
                    runStep(step, context, extra ? withSpare : commands, sample, kLength);
                } catch (ProgramFailedException e) {
                    if (!runAgainAlone || !BbtoolsCommands.ranOutOfMemory(e)) {
                        throw outOfMemoryOrAsIs(e, sample, share);
                    }
                    setAside.put(sample.getName(), step);
                    monitor.warning("Warning: " + e.program() + " ran out of memory on sample " + sample.getName()
                            + " with -Xmx" + share.memoryGiB() + "g; it will be run again after the other samples,"
                            + " alone, with -Xmx" + alone.memoryGiB() + "g");
                } finally {
                    if (extra) {
                        spare.release();
                    }
                }
            }, ParallelStageRunner.Progress.NONE);
            if (step.completed != null) {
                monitor.info(step.completed);
            }
        }

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
                        runStep(step, context, whole, sample, kLength);
                    } catch (ProgramFailedException e) {
                        throw outOfMemoryOrAsIs(e, sample, alone);
                    }
                }
            }
            monitor.info("Completed sample " + sample.getName());
        }
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

    private static void runStep(ReadStep step, PipelineContext context, BbtoolsCommands commands, Sample sample,
                                int kLength) {
        switch (step) {
            case FIRST_BAIT -> bait(context, commands, sample, kLength);
            case SECOND_BAIT -> secondBait(context, commands, sample);
            case ASSEMBLY -> assemble(context, commands, sample);
            case MERGE -> merge(context, commands, sample);
        }
    }

    private static void merge(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        SampleFiles files = context.directories().sample(sample.getName());
        run(context, "BBMerge", commands.mergePairs(files.targetMatches(),
                files.mergedReads(),
                files.unmergedReads()));
    }

    private static List<Sample> fastqSamples(PipelineContext context) {
        return context.samples().values().stream()
                .filter(Sample::isFastq)
                .sorted(Comparator.comparing(Sample::getName))
                .toList();
    }

    private static void bait(PipelineContext context, BbtoolsCommands commands, Sample sample, int kLength) {
        context.directories().createSampleDirectory(sample.getName());
        SampleFiles files = context.directories().sample(sample.getName());
        run(context, "BBDuk", commands.firstBait(
                context.directories().primerDatabase(),
                sample.files(),
                files.targetMatches(),
                kLength,
                context.config().mismatches()
        ));
    }

    private static void secondBait(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        SampleFiles files = context.directories().sample(sample.getName());
        run(context, "BBDuk", commands.secondBait(
                files.targetMatches(),
                sample.files(),
                files.doubleTargetMatches()
        ));
    }

    private static void assemble(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        SampleFiles files = context.directories().sample(sample.getName());
        Path output = files.assembly();
        sample.setAssemblyFile(output);
        run(context, "Tadpole", commands.assemble(files.doubleTargetMatches(), output,
                context.config().tadpole()));
    }

    private static void run(PipelineContext context, String program, BbtoolsCommands.Command command) {
        command.run(context.monitor(), program);
    }
}
