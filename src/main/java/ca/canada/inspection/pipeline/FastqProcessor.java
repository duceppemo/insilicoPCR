package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.util.ProcessRunner;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class FastqProcessor {
    public void process(PipelineContext context) {
        List<Sample> samples = fastqSamples(context);
        if (samples.isEmpty()) {
            return;
        }

        System.out.println("FastQ files identified, conducting baiting and assembly");
        int kLength = context.primers().values().stream().mapToInt(String::length).min()
                .orElseThrow(() -> new PipelineException("Primer dictionary is empty"));
        BbtoolsCommands commands = new BbtoolsCommands(context.dependencies().javaCommand(),
                context.dependencies().bbtoolsJar(), context.childJavaMemoryGiB(), context.config().threads());

        ParallelStageRunner.run("First BBDuk baiting", samples, context.config().threads(),
                sample -> bait(context, commands, sample, kLength));
        ParallelStageRunner.run("Second BBDuk baiting", samples, context.config().threads(),
                sample -> secondBait(context, commands, sample));
        ParallelStageRunner.run("Tadpole assembly", samples, context.config().threads(),
                sample -> assemble(context, commands, sample));
        List<Sample> paired = samples.stream().filter(sample -> sample.files().size() == 2).toList();
        ParallelStageRunner.run("BBMerge of baited pairs", paired, context.config().threads(),
                sample -> merge(context, commands, sample));
    }

    private static void merge(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        Path sampleDir = sampleDirectory(context, sample);
        run(context, commands.mergePairs(BbtoolsCommands.targetMatches(sampleDir, sample.getName()),
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
        run(context, commands.firstBait(
                context.directories().primerDatabase(),
                sample.files(),
                BbtoolsCommands.targetMatches(sampleDir, sample.getName()),
                kLength,
                context.config().mismatches()
        ));
    }

    private static void secondBait(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        Path sampleDir = sampleDirectory(context, sample);
        run(context, commands.secondBait(
                BbtoolsCommands.targetMatches(sampleDir, sample.getName()),
                sample.files(),
                BbtoolsCommands.doubleTargetMatches(sampleDir, sample.getName())
        ));
    }

    private static void assemble(PipelineContext context, BbtoolsCommands commands, Sample sample) {
        Path sampleDir = sampleDirectory(context, sample);
        Path output = BbtoolsCommands.assembly(sampleDir, sample.getName());
        sample.setAssemblyFile(output);
        run(context, commands.assemble(BbtoolsCommands.doubleTargetMatches(sampleDir, sample.getName()), output,
                context.config().tadpole()));
    }

    private static Path sampleDirectory(PipelineContext context, Sample sample) {
        return context.directories().detailed().resolve(sample.getName());
    }

    private static void run(PipelineContext context, List<String> command) {
        ProcessRunner.run(context.dependencies().bbmapDirectory(), command.toArray(String[]::new));
    }
}
