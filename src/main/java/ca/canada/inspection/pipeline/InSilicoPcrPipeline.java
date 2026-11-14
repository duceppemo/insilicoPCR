package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pipeline.RunMonitor.Step;
import ca.canada.inspection.util.InputCheck;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** One in silico PCR run, for the command line and for the application alike. */
public final class InSilicoPcrPipeline {
    private final PipelineConfig config;
    private final RunMonitor monitor;
    private final SampleRepository samples = new SampleRepository();
    private final PrimerService primers = new PrimerService();
    private final FastqProcessor fastqProcessor = new FastqProcessor();
    private final BlastDatabaseBuilder blastDatabaseBuilder = new BlastDatabaseBuilder();
    private final BlastRunner blastRunner = new BlastRunner();
    private final ReportGenerator reportGenerator = new ReportGenerator();

    /** A run that reports to the console. */
    public InSilicoPcrPipeline(PipelineConfig config) {
        this(config, RunMonitor.CONSOLE);
    }

    public InSilicoPcrPipeline(PipelineConfig config, RunMonitor monitor) {
        this.config = Objects.requireNonNull(config, "config");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    public void run() {
        long startTime = System.nanoTime();
        monitor.info("Beginning Program Run");
        RuntimeDependencies dependencies = RuntimeDependencies.discover();
        PipelineDirectories directories = PipelineDirectories.create(config.output());
        monitor.stepDone(Step.DEPENDENCIES);

        monitor.info("Checking the input files (" + (config.inputCheck() == InputCheck.Depth.FULL
                ? "every read; --input-check quick reads the first one only" : "first read only") + ")");
        InputCheck.Result checked = samples.check(config);
        checked.warnings().forEach(line -> monitor.warning("Warning: " + line));
        Map<String, Sample> sampleMap = new LinkedHashMap<>(checked.samples());
        monitor.stepDone(Step.INPUT_CHECK);

        PrimerService.PreparedPrimers prepared = primers.loadAndPrepare(config, directories);
        PipelineContext context = new PipelineContext(
                config,
                dependencies,
                directories,
                sampleMap,
                prepared.primers(),
                prepared.expectedSizes(),
                ChildJavaMemory.budgetGiB(),
                checked.issues(),
                monitor
        );
        logRuntime(context);
        monitor.stepDone(Step.PRIMERS);

        fastqProcessor.process(context);
        monitor.stepDone(Step.READS);
        blastDatabaseBuilder.build(context);
        monitor.stepDone(Step.BLAST_DATABASE);
        blastRunner.run(context);
        monitor.info("Completed BLAST");
        monitor.stepDone(Step.BLAST);
        reportGenerator.generate(context);
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime);
        monitor.info("Done in " + seconds + " seconds");
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
