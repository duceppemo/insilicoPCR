package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.insilicopcr.Methods;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.BlastReports;
import ca.canada.inspection.pcr.ConsolidatedReport;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import javafx.concurrent.Task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
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

            HashMap<String, Sample> sampleDict = Methods.createSampleDict(config.inputFile());
            Methods.logMessage(config.outputField(), "Created Sample Dictionary");
            updateProgress(3, 13);

            HashMap<String, String> primerDict = Methods.parseFastaToDictionary(config.primerFile());
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

            // Read-level search needs the primer BLAST database, which lives in the temp directory.
            Map<String, ReadEvidence> readEvidence = Map.of();
            if (fastqPresent) {
                readEvidence = ReadLevelStage.run(new ReadLevelStage.Settings(
                                AppPaths.executable(dependencies.blastLocation(), "blastn"), cleanedPrimers,
                                config.evalue(), config.threads()),
                        directories.detailedDir(), sampleDict, primerDict, config.ampliconOptions());
                Methods.logMessage(config.outputField(), "Completed read-level amplicon search");
            }

            cleanupTempDirectory(directories.tempDir(), config.outputField());

            Methods.addContigDict(sampleDict);
            Methods.logMessage(config.outputField(), "Completed Contig Dictionary");
            BlastReports.attachHits(directories.detailedDir(), sampleDict, primerDict, config.ampliconOptions());
            Methods.logMessage(config.outputField(), "Parsed BLAST output");
            updateProgress(11, 13);

            Path report = ConsolidatedReport.write(directories.consolidatedDir(), sampleDict, primerDict,
                    config.ampliconOptions(), FastqDiagnosticsStage.readLevelRows(sampleDict, primerDict,
                            config.ampliconOptions(), readEvidence));
            Methods.logMessage(config.outputField(), "Created Consolidated Report");
            var diagnostics = FastqDiagnosticsStage.run(report, sampleDict, primerDict, config.ampliconOptions(),
                    readEvidence);
            diagnostics.logLines().forEach(line -> Methods.logMessage(config.outputField(), line));
            updateProgress(12, 13);

            LogFiles.ensureQaLog(config.outDir(), config.inputFile(), config.primerFile(), dependencies);
            LogFiles.appendQaLog(config.outDir(), diagnostics.qaLogLines(TadpoleOptions.DEFAULTS));
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
