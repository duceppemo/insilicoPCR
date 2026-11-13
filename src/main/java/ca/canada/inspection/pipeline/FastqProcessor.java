package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class FastqProcessor {
    public void process(PipelineContext context) {
        List<Sample> samples = fastqSamples(context);
        if (samples.isEmpty()) {
            return;
        }

        context.monitor().info("FastQ files identified, conducting baiting and assembly");
        int kLength = context.primers().values().stream().mapToInt(String::length).min()
                .orElseThrow(() -> new PipelineException("Primer dictionary is empty"));
        BbtoolsCommands commands = new BbtoolsCommands(context.dependencies().javaCommand(),
                context.dependencies().bbtoolsJar(), context.childJavaMemoryGiB(), context.config().threads());

        stage(context, "First BBDuk baiting", samples, sample -> bait(context, commands, sample, kLength));
        context.monitor().info("Completed First Baiting");
        stage(context, "Second BBDuk baiting", samples, sample -> secondBait(context, commands, sample));
        context.monitor().info("Completed Second Baiting");
        stage(context, "Tadpole assembly", samples, sample -> assemble(context, commands, sample));
        List<Sample> paired = samples.stream().filter(sample -> sample.files().size() == 2).toList();
        stage(context, "BBMerge of baited pairs", paired, sample -> merge(context, commands, sample));
        context.monitor().info("Completed Assembly");
    }

    private static void stage(PipelineContext context, String name, List<Sample> samples, Consumer<Sample> action) {
        ParallelStageRunner.run(name, samples, context.config().threads(), context.monitor(), action,
                ParallelStageRunner.Progress.NONE);
    }

    private static void merge(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        Path sampleDir = sampleDirectory(context, sample);
        run(context, "BBMerge", commands.mergePairs(BbtoolsCommands.targetMatches(sampleDir, sample.getName()),
                BbtoolsCommands.mergedReads(sampleDir, sample.getName()),
                BbtoolsCommands.unmergedReads(sampleDir, sample.getName())));
    }

    private static List<Sample> fastqSamples(PipelineContext context) {
        return context.samples().values().stream()
                .filter(Sample::isFastq)
                .sorted(Comparator.comparing(Sample::getName))
                .toList();
    }

    private static void bait(PipelineContext context, BbtoolsCommands commands, Sample sample, int kLength) {
        context.directories().createSampleDirectory(sample.getName());
        Path sampleDir = sampleDirectory(context, sample);
        run(context, "BBDuk", commands.firstBait(
                context.directories().primerDatabase(),
                sample.files(),
                BbtoolsCommands.targetMatches(sampleDir, sample.getName()),
                kLength,
                context.config().mismatches()
        ));
    }

    private static void secondBait(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        Path sampleDir = sampleDirectory(context, sample);
        run(context, "BBDuk", commands.secondBait(
                BbtoolsCommands.targetMatches(sampleDir, sample.getName()),
                sample.files(),
                BbtoolsCommands.doubleTargetMatches(sampleDir, sample.getName())
        ));
    }

    private static void assemble(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        Path sampleDir = sampleDirectory(context, sample);
        Path output = BbtoolsCommands.assembly(sampleDir, sample.getName());
        sample.setAssemblyFile(output);
        run(context, "Tadpole", commands.assemble(BbtoolsCommands.doubleTargetMatches(sampleDir, sample.getName()), output,
                context.config().tadpole()));
    }

    private static Path sampleDirectory(PipelineContext context, Sample sample) {
        return context.directories().detailed().resolve(sample.getName());
    }

    private static void run(PipelineContext context, String program, List<String> command) {
        ExternalProgram.run(context.monitor(), context.dependencies().bbmapDirectory(), command, program);
    }
}
