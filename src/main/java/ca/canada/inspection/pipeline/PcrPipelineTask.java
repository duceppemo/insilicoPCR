package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.insilicopcr.Methods;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.BlastReports;
import ca.canada.inspection.pcr.ConsolidatedReport;
import ca.canada.inspection.pcr.HtmlReport;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.util.InputCheck;
import javafx.concurrent.Task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class PcrPipelineTask extends Task<Void> {
    private final PcrRunConfig config;
    private final ExternalProcessTracker processTracker;
    private BlastPipeline blastPipeline;
    private DependencyContext dependencies;

    public PcrPipelineTask(PcrRunConfig config, ExternalProcessTracker processTracker) {
        this.config = config;
        this.processTracker = processTracker;
    }

    @Override
    protected Void call() {
        RunDirectories directories = null;
        try {
            var startTime = System.nanoTime();
            Methods.logMessage(config.outputField(), "Beginning Program Run");

            dependencies = DependencyContext.discover(config.outputField());
            Methods.logMessage(config.outputField(), "Found Dependencies");
            updateProgress(1, 13);

            directories = RunDirectories.createUnder(config.outDir());
            Methods.logMessage(config.outputField(), "Created Directories");
            updateProgress(2, 13);

            Methods.logMessage(config.outputField(), "Checking the input files (every read)");
            InputCheck.Result checked = InputCheck.scan(config.inputFile(), InputCheck.Depth.FULL, config.threads());
            checked.warnings().forEach(line -> Methods.logMessage(config.outputField(), "Warning: " + line));
            HashMap<String, Sample> sampleDict = new HashMap<>(checked.samples());
            Methods.logMessage(config.outputField(), "Created Sample Dictionary");
            updateProgress(3, 13);

            // Same reader as the command line: IDs end at the first blank, and the rest of
            // the header can declare product sizes.
            PrimerService.PrimerFile primerFile = PrimerService.read(config.primerFile());
            HashMap<String, String> primerDict = new HashMap<>(primerFile.sequences());
            Methods.logMessage(config.outputField(), "Created Primer Dictionary");
            updateProgress(4, 13);

            Path cleanedPrimers = Methods.processPrimers(primerDict, config.outputField(), directories.tempDir(), java.io.File.separator);
            Methods.logMessage(config.outputField(), "Finished Formatting Primers");
            updateProgress(5, 13);

            var fastqPresent = sampleDict.values().stream().anyMatch(sample -> "fastq".equals(sample.getFileType()));
            if (fastqPresent) {
                Methods.logMessage(config.outputField(), "FastQ files identified, conducting baiting and assembly");
                var bbmap = new BbmapPipeline(config, directories, dependencies, processTracker, primerDict, sampleDict, cleanedPrimers);
                bbmap.runFirstBait(this);
                Methods.logMessage(config.outputField(), "Completed First Baiting");
                updateProgress(6, 13);
                bbmap.runSecondBait(this);
                Methods.logMessage(config.outputField(), "Completed Second Baiting");
                updateProgress(7, 13);
                bbmap.runAssembly(this);
                Methods.logMessage(config.outputField(), "Completed Assembly");
                updateProgress(8, 13);
            }

            if (!System.getProperty("os.name").contains("Windows")) {
                Methods.makeExecutable(dependencies.blastLocation());
            }
            Methods.makeBlastDB(cleanedPrimers, dependencies.blastLocation(), config.outputField());
            Methods.logMessage(config.outputField(), "Completed Database Creation");
            updateProgress(9, 13);

            blastPipeline = new BlastPipeline(config, directories, dependencies, processTracker, sampleDict, cleanedPrimers);
            blastPipeline.run();
            Methods.logMessage(config.outputField(), "Completed BLAST");
            updateProgress(10, 13);

            // The read-level search and the read extension need the primer BLAST database, which
            // lives in the temp directory; the extension also needs each sample's contig hits, to
            // leave alone the assays the mini-assembly reports.
            Map<String, ReadEvidence> readEvidence = Map.of();
            ReadLevelStage.Settings search = new ReadLevelStage.Settings(
                    AppPaths.executable(dependencies.blastLocation(), "blastn"), cleanedPrimers,
                    config.evalue(), config.threads(), config.readMinFraction(),
                    processTracker, line -> Methods.logMessage(config.outputField(), line),
                    this::isCancelled);
            if (fastqPresent) {
                readEvidence = ReadLevelStage.run(search, directories.detailedDir(), sampleDict, primerDict,
                        config.ampliconOptions());
                Methods.logMessage(config.outputField(), "Completed read-level amplicon search");
            }

            Methods.addContigDict(sampleDict);
            Methods.logMessage(config.outputField(), "Completed Contig Dictionary");
            BlastReports.attachHits(directories.detailedDir(), sampleDict, primerDict, config.ampliconOptions());
            Methods.logMessage(config.outputField(), "Parsed BLAST output");

            ReadExtensionStage.Result extension = ReadExtensionStage.Result.NONE;
            if (fastqPresent && config.readExtension() != ReadExtensionCaller.Mode.OFF) {
                extension = ReadExtensionStage.run(new ReadExtensionStage.Settings(search,
                                new BbtoolsCommands(dependencies.javaCommand(), dependencies.bbtoolsJar(),
                                        ChildJavaMemory.recommendedGiB(), config.threads()),
                                dependencies.bbtoolsLocation(), config.readExtension(),
                                primerFile.expectedSizes()),
                        directories.detailedDir(), sampleDict, primerDict, config.ampliconOptions(), readEvidence);
                processTracker.clear();
                Methods.logMessage(config.outputField(), "Completed read extension");
            }

            cleanupTempDirectory(directories.tempDir(), config.outputField());
            updateProgress(11, 13);

            Path report = ConsolidatedReport.write(directories.consolidatedDir(), sampleDict, primerDict,
                    config.ampliconOptions(), FastqDiagnosticsStage.extraRows(sampleDict, primerDict,
                            config.ampliconOptions(), readEvidence, extension), primerFile.expectedSizes());
            Methods.logMessage(config.outputField(), "Created Consolidated Report");
            var diagnostics = FastqDiagnosticsStage.run(report, sampleDict, primerDict, config.ampliconOptions(),
                    readEvidence, extension);
            diagnostics.logLines().forEach(line -> Methods.logMessage(config.outputField(), line));
            Path html = HtmlReport.write(report, sampleDict.keySet(), RunDetails.fastqSampleNames(sampleDict),
                    primerDict, primerFile.expectedSizes(), diagnostics.findings(),
                    RunDetails.of(Dispatcher.VERSION, config.inputFile(), config.primerFile(), config.outDir(),
                            config.ampliconOptions(), fastqPresent ? config.readMinFraction() : null,
                            config.readExtension()),
                    checked.issues());
            Methods.logMessage(config.outputField(), "Created HTML Report: " + html);
            updateProgress(12, 13);

            // Rewritten each run, like the command line: an output folder reused by several
            // runs must not keep the first run's header with every later run's FASTQ lines.
            LogFiles.writeQaLog(config.outDir(), config.inputFile(), config.primerFile(), dependencies);
            List<String> qaLines = new ArrayList<>(RunDetails.inputIssueLines(checked.issues()));
            qaLines.addAll(primerFile.expectedSizes().qaLogLines());
            qaLines.addAll(diagnostics.qaLogLines(config.tadpole(), config.readMinFraction(),
                    config.readExtension()));
            LogFiles.appendQaLog(config.outDir(), qaLines);
            var seconds = (System.nanoTime() - startTime) / 1_000_000_000L;
            Methods.logMessage(config.outputField(), "Done in " + seconds + " seconds");
            updateProgress(13, 13);
            return null;
        } finally {
            if (directories != null) {
                cleanupTempDirectory(directories.tempDir(), config.outputField());
            }
        }
    }

    public DependencyContext dependencies() {
        return dependencies;
    }

    public void shutdownNow() {
        if (blastPipeline != null) {
            blastPipeline.shutdownNow();
        }
        processTracker.destroyAll();
    }

    private static void cleanupTempDirectory(Path tempDir, javafx.scene.control.TextArea outputField) {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new TempCleanupException(e);
                        }
                    });
        } catch (TempCleanupException | IOException e) {
            Methods.logMessage(outputField, "Unable to delete temporary directory " + tempDir + ": " + e.getMessage());
        }
    }

    private static final class TempCleanupException extends RuntimeException {
        private TempCleanupException(Throwable cause) {
            super(cause);
        }
    }
}
