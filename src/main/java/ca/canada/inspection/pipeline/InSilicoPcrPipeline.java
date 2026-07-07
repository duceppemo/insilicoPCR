package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** High-level orchestration for one in silico PCR run. */
public final class InSilicoPcrPipeline {
    private final PipelineConfig config;
    private final SampleRepository samples = new SampleRepository();
    private final PrimerService primers = new PrimerService();
    private final FastqProcessor fastqProcessor = new FastqProcessor();
    private final BlastDatabaseBuilder blastDatabaseBuilder = new BlastDatabaseBuilder();
    private final BlastRunner blastRunner = new BlastRunner();
    private final ReportGenerator reportGenerator = new ReportGenerator();

    public InSilicoPcrPipeline(PipelineConfig config) {
        this.config = config;
    }

    public void run() {
        long startTime = System.nanoTime();
        System.out.println("Beginning Program Run");

        RuntimeDependencies dependencies = RuntimeDependencies.discover();
        PipelineDirectories directories = PipelineDirectories.create(config.output());
        Map<String, Sample> sampleMap = samples.load(config);
        Map<String, String> primerMap = primers.loadAndPrepare(config, directories);

        PipelineContext context = new PipelineContext(
                config,
                dependencies,
                directories,
                sampleMap,
                primerMap,
                ChildJavaMemory.recommendedGiB()
        );

        logRuntime(context);
        fastqProcessor.process(context);
        blastDatabaseBuilder.build(context);
        blastRunner.run(context);
        reportGenerator.generate(context);

        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime);
        System.out.println("Done in " + seconds + " seconds");
    }

    private static void logRuntime(PipelineContext context) {
        System.out.println("Application root: " + context.dependencies().appRoot());
        System.out.println("BBMap: " + context.dependencies().bbmapDirectory());
        System.out.println("BLAST: " + context.dependencies().blastBinDirectory());
        System.out.println("Java: " + context.dependencies().javaCommand());
        System.out.println("Child Java memory: -Xmx" + context.childJavaMemoryGiB() + "g");
    }
}
