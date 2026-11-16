package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.pipeline.RunMonitor.Step;
import ca.canada.inspection.util.InputCheck;
import ca.canada.inspection.util.SequenceFileUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * One in silico PCR run, for the command line and for the application alike. Its steps
 * ({@link Step}), in order, are all here; each is a class with a static entry point.
 */
public final class InSilicoPcrPipeline {
    private final PipelineConfig config;
    private final RunMonitor monitor;

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
        // One run at a time in an output folder, held until this run ends, however it ends.
        try (OutputLock ignored = OutputLock.take(directories.output(), monitor::warning)) {
            return run(startTime, dependencies, directories);
        }
    }

    private RunResult run(long startTime, RuntimeDependencies dependencies, PipelineDirectories directories) {
        done(Step.DEPENDENCIES);
        // On Windows, the output folder and the installation first: the input check can take minutes, and
        // its samples are checked once they are known (below).
        WindowsPathLimit.check(directories, List.of());
        WindowsCodePage.check(config, dependencies, List.of());

        // The primer file first: a mistake there is found at once, not after reading every input.
        PrimerPanel primers = PrimerService.loadAndPrepare(config, directories);
        done(Step.PRIMERS);

        monitor.info("Checking the input files (" + (config.inputCheck() == InputCheck.Depth.FULL
                ? "every read; --input-check quick reads the first one only" : "first read only") + ")");
        InputCheck.Result checked = InputCheck.scan(config.input(), config.inputCheck(), config.threads(), config.output());
        if (checked.files() == 0) {
            throw new PipelineException(InputCheck.noInputFiles(config.input()));
        }
        checked.warnings().forEach(line -> monitor.warning("Warning: " + line));
        List<InputCheck.Issue> issues = new ArrayList<>(checked.issues());
        Map<String, Sample> sampleMap = new LinkedHashMap<>(checked.samples());
        // A sample whose files' names would be too long for the file system stopped the run, every sample with
        // it (the reads of one hung it, BBDuk never ending): it is left out.
        for (InputCheck.Issue issue : FileNameLimit.tooLong(directories, sampleMap.values())) {
            sampleMap.remove(issue.sample());
            issues.add(issue);
            monitor.warning("Warning: " + issue.line());
        }
        if (sampleMap.isEmpty()) {
            // Every file faulty: empty reports ending as if the run had worked would be read as a result
            // (a workflow manager running one job per sample, given one truncated file, saw exit code 0).
            throw new PipelineException("No sample of " + config.input() + " can be analysed: every input file"
                    + " is faulty (each is named in a warning above)");
        }
        PipelineContext context = new PipelineContext(config, dependencies, directories, sampleMap, primers,
                // The BLAST step adds an assembly blastn refuses (BlastRunner).
                ChildJavaMemory.budgetGiB(config.bbtoolsMemoryGiB()), issues, monitor);
        logRuntime(context);
        RunSettings.lines(config, context.hasFastqSamples()).forEach(monitor::info);
        monitor.info("  Tools: " + dependencies.versions());
        // Before any program runs: on Windows, a path too long fails them all, with an obscure error.
        WindowsPathLimit.check(directories, sampleMap.values());
        WindowsCodePage.check(config, dependencies, sampleMap.values());
        done(Step.INPUT_CHECK);

        // The primers' BLAST database first: the baiting of the reads searches its first catch for them.
        BlastDatabaseBuilder.build(context);
        done(Step.BLAST_DATABASE);

        // FASTQ samples: baited and assembled into a mini-assembly, searched like an assembly.
        FastqProcessor.process(context);
        done(Step.READS);

        BlastRunner.run(context);
        ContigService.load(context);
        monitor.info("Completed BLAST");
        done(Step.BLAST);

        // FASTQ samples: products the mini-assembly missed, looked for in the reads themselves.
        ReadLevelStage.Settings search = ReadLevelStage.Settings.of(context);
        Map<String, ReadEvidence> readEvidence = Map.of();
        if (context.hasFastqSamples()) {
            readEvidence = ReadLevelStage.run(search, directories, sampleMap, context.primers(),
                    config.ampliconOptions());
            monitor.info("Completed read-level amplicon search");
        }
        done(Step.READ_SEARCH);

        // FASTQ samples: products longer than a read, looked for by extending reads.
        ReadExtensionStage.Result extension = ReadExtensionStage.Result.NONE;
        if (context.hasFastqSamples() && config.readExtension() != ReadExtensionCaller.Mode.OFF) {
            extension = ReadExtensionStage.run(ReadExtensionStage.Settings.of(context, search), directories,
                    sampleMap, context.primers(), config.ampliconOptions(), readEvidence);
            monitor.info("Completed read extension");
        }
        done(Step.READ_EXTENSION);

        RunResult result = ReportGenerator.write(context, readEvidence, extension);
        // QAlog.txt is written: the run is finished, whatever happens from here.
        monitor.stepDone(Step.REPORTS);

        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime);
        monitor.info("Done in " + seconds + " seconds");
        return result;
    }

    /**
     * Reports {@code step} done, then stops a cancelled run: no step after it starts, and a
     * stopped run writes no report or QAlog.txt (a step may go on after a failed program).
     */
    private void done(Step step) {
        monitor.stepDone(step);
        if (monitor.isCancelled()) {
            throw new CancellationException("The run was cancelled.");
        }
    }

    private static void logRuntime(PipelineContext context) {
        RunMonitor monitor = context.monitor();
        int processors = Runtime.getRuntime().availableProcessors();
        if (context.config().threads() > processors) {
            // -t 1000 on 64 processors: 4 BBDuk runs of 250 threads each, slower, not faster.
            monitor.warning("Warning: -t " + context.config().threads() + " is more than the " + processors
                    + " processors the program sees: the threads take turns on them");
        }
        monitor.info("Application root: " + context.dependencies().appRoot());
        monitor.info("BBMap: " + context.dependencies().bbmapDirectory());
        monitor.info("BLAST: " + context.dependencies().blastBinDirectory());
        monitor.info("Java: " + context.dependencies().javaCommand());
        monitor.info("Child Java memory: " + context.childJavaMemoryBudgetGiB() + " GB for the BBTools programs"
                + " running at once, at most " + ChildJavaMemory.MAX_CHILD_GIB + " GB each"
                + (context.config().bbtoolsMemoryGiB() > 0 ? " (--bbtools-memory)"
                : " (three quarters of the memory insilicoPCR leaves free; --bbtools-memory sets it)"));
    }
}
