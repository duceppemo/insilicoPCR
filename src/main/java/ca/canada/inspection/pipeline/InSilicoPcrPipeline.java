package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.BlastReports;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.pipeline.RunMonitor.Step;
import ca.canada.inspection.util.InputCheck;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * One in silico PCR run, for the command line and for the application alike. Its steps
 * ({@link Step}), in order, are all here; each is a class with a static entry point.
 */
public final class InSilicoPcrPipeline {
    private final PipelineConfig config;
    private final RunMonitor monitor;

    /** A run that reports to the console. */
    public InSilicoPcrPipeline(PipelineConfig config) {
        this(config, RunMonitor.CONSOLE);
    }

    public InSilicoPcrPipeline(PipelineConfig config, RunMonitor monitor) {
        this.config = Objects.requireNonNull(config, "config");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    /**
     * Runs every step. {@code QAlog.txt} is written at the end, so a run that fails or is
     * cancelled leaves the log of the last finished run in the folder, with the report it names.
     */
    public RunResult run() {
        long startTime = System.nanoTime();
        monitor.info("Beginning Program Run");
        RuntimeDependencies dependencies = RuntimeDependencies.discover();
        PipelineDirectories directories = PipelineDirectories.create(config.output());
        monitor.stepDone(Step.DEPENDENCIES);

        // The primer file first: a mistake there is found at once, not after reading every input.
        PrimerPanel primers = PrimerService.loadAndPrepare(config, directories);
        monitor.stepDone(Step.PRIMERS);

        monitor.info("Checking the input files (" + (config.inputCheck() == InputCheck.Depth.FULL
                ? "every read; --input-check quick reads the first one only" : "first read only") + ")");
        InputCheck.Result checked = InputCheck.scan(config.input(), config.inputCheck(), config.threads());
        checked.warnings().forEach(line -> monitor.warning("Warning: " + line));
        Map<String, Sample> sampleMap = new LinkedHashMap<>(checked.samples());
        PipelineContext context = new PipelineContext(config, dependencies, directories, sampleMap, primers,
                ChildJavaMemory.budgetGiB(), checked.issues(), monitor);
        logRuntime(context);
        monitor.stepDone(Step.INPUT_CHECK);

        // FASTQ samples: baited and assembled into a mini-assembly, searched like an assembly.
        FastqProcessor.process(context);
        monitor.stepDone(Step.READS);

        BlastDatabaseBuilder.build(context);
        monitor.stepDone(Step.BLAST_DATABASE);

        BlastRunner.run(context);
        ContigService.load(context);
        BlastReports.attachHits(directories.detailed(), sampleMap, context.primers(),
                config.ampliconOptions());
        monitor.info("Completed BLAST");
        monitor.stepDone(Step.BLAST);

        // FASTQ samples: products the mini-assembly missed, looked for in the reads themselves.
        ReadLevelStage.Settings search = ReadLevelStage.Settings.of(context);
        Map<String, ReadEvidence> readEvidence = Map.of();
        if (context.hasFastqSamples()) {
            readEvidence = ReadLevelStage.run(search, directories.detailed(), sampleMap, context.primers(),
                    config.ampliconOptions());
            monitor.info("Completed read-level amplicon search");
        }
        monitor.stepDone(Step.READ_SEARCH);

        // FASTQ samples: products longer than a read, looked for by extending reads.
        ReadExtensionStage.Result extension = ReadExtensionStage.Result.NONE;
        if (context.hasFastqSamples() && config.readExtension() != ReadExtensionCaller.Mode.OFF) {
            extension = ReadExtensionStage.run(ReadExtensionStage.Settings.of(context, search), directories.detailed(),
                    sampleMap, context.primers(), config.ampliconOptions(), readEvidence);
            monitor.info("Completed read extension");
        }
        monitor.stepDone(Step.READ_EXTENSION);

        RunResult result = ReportGenerator.write(context, readEvidence, extension);
        monitor.stepDone(Step.REPORTS);

        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime);
        monitor.info("Done in " + seconds + " seconds");
        return result;
    }

    private static void logRuntime(PipelineContext context) {
        RunMonitor monitor = context.monitor();
        monitor.info("Application root: " + context.dependencies().appRoot());
        monitor.info("BBMap: " + context.dependencies().bbmapDirectory());
        monitor.info("BLAST: " + context.dependencies().blastBinDirectory());
        monitor.info("Java: " + context.dependencies().javaCommand());
        monitor.info("Child Java memory: " + context.childJavaMemoryBudgetGiB() + " GB for the BBTools programs"
                + " running at once, at most " + ChildJavaMemory.MAX_CHILD_GIB + " GB each");
    }
}
