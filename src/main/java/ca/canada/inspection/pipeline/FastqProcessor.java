package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.util.ProcessRunner;

import java.nio.file.Path;
import java.util.ArrayList;
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

        ParallelStageRunner.run("First BBDuk baiting", samples, context.config().threads(),
                sample -> bait(context, sample, kLength));
        ParallelStageRunner.run("Second BBDuk baiting", samples, context.config().threads(),
                sample -> secondBait(context, sample));
        ParallelStageRunner.run("Tadpole assembly", samples, context.config().threads(),
                sample -> assemble(context, sample));
    }

    private static List<Sample> fastqSamples(PipelineContext context) {
        return context.samples().values().stream()
                .filter(Sample::isFastq)
                .sorted(Comparator.comparing(Sample::getName))
                .toList();
    }

    private static void bait(PipelineContext context, Sample sample, int kLength) {
        Path sampleDir = context.directories().detailed().resolve(sample.getName());
        context.directories().createSampleDirectory(sample.getName());
        List<String> command = bbdukCommand(
                context,
                sample,
                context.directories().primerDatabase(),
                sampleDir.resolve(sample.getName() + "_targetMatches.fastq.gz"),
                kLength
        );
        ProcessRunner.run(context.dependencies().bbmapDirectory(), command.toArray(String[]::new));
    }

    private static void secondBait(PipelineContext context, Sample sample) {
        Path sampleDir = context.directories().detailed().resolve(sample.getName());
        List<String> command = bbdukCommand(
                context,
                sample,
                sampleDir.resolve(sample.getName() + "_targetMatches.fastq.gz"),
                sampleDir.resolve(sample.getName() + "_doubleTargetMatches.fastq.gz"),
                null
        );
        ProcessRunner.run(context.dependencies().bbmapDirectory(), command.toArray(String[]::new));
    }

    private static void assemble(PipelineContext context, Sample sample) {
        Path sampleDir = context.directories().detailed().resolve(sample.getName());
        Path input = sampleDir.resolve(sample.getName() + "_doubleTargetMatches.fastq.gz");
        Path output = sampleDir.resolve(sample.getName() + "_assembly.fasta");
        sample.setAssemblyFile(output);
        ProcessRunner.run(context.dependencies().bbmapDirectory(),
                context.dependencies().javaCommand(), "-ea", "-Xmx" + context.childJavaMemoryGiB() + "g",
                "-cp", context.dependencies().bbtoolsJar().toString(), "assemble.Tadpole",
                "in=" + input, "out=" + output, "overwrite=t", "threads=" + context.config().threads());
    }

    private static List<String> bbdukCommand(PipelineContext context, Sample sample, Path ref, Path output, Integer kLength) {
        ArrayList<String> command = new ArrayList<>(List.of(
                context.dependencies().javaCommand(), "-ea", "-Xmx" + context.childJavaMemoryGiB() + "g",
                "-cp", context.dependencies().bbtoolsJar().toString(), "jgi.BBDuk",
                "ref=" + ref,
                "hdist=" + context.config().mismatches(),
                "threads=" + context.config().threads(),
                "overwrite=t",
                "interleaved=t"
        ));
        if (kLength != null) {
            command.add("k=" + kLength);
        }
        if (sample.getFiles().size() == 2) {
            command.add("in1=" + sample.getFiles().getFirst());
            command.add("in2=" + sample.getFiles().get(1));
        } else {
            command.add("in=" + sample.getFiles().getFirst());
        }
        command.add("outm=" + output);
        return command;
    }
}
