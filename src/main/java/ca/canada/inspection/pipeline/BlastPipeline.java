package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.insilicopcr.MessageConsumer;
import ca.canada.inspection.insilicopcr.Methods;
import ca.canada.inspection.insilicopcr.Sample;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class BlastPipeline {
    private final PcrRunConfig config;
    private final RunDirectories directories;
    private final DependencyContext dependencies;
    private final ExternalProcessTracker processTracker;
    private final Map<String, Sample> sampleDict;
    private ThreadPoolExecutor pool;

    public BlastPipeline(PcrRunConfig config,
                         RunDirectories directories,
                         DependencyContext dependencies,
                         ExternalProcessTracker processTracker,
                         Map<String, Sample> sampleDict) {
        this.config = config;
        this.directories = directories;
        this.dependencies = dependencies;
        this.processTracker = processTracker;
        this.sampleDict = sampleDict;
    }

    public void run() {
        pool = new ThreadPoolExecutor(config.threads(), Integer.MAX_VALUE, Long.MAX_VALUE, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        var primers = config.outDir().resolve("primer_tmp.fasta");
        for (var sample : sampleDict.values()) {
            if ("fastq".equals(sample.getFileType())) {
                pool.submit(() -> runBlast(primers, sample.getAssemblyFile()));
            } else {
                for (var file : sample.getFiles()) {
                    pool.submit(() -> runBlast(primers, file));
                }
            }
        }
        try {
            pool.shutdown();
            while (!pool.getQueue().isEmpty()) {
                updateBlastProgress(pool.getCompletedTaskCount() + config.threads(), pool.getTaskCount());
            }
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for BLAST tasks", e);
        } finally {
            processTracker.clear();
        }
        updateBlastProgress(1, 1);
    }

    private void updateBlastProgress(long workDone, long totalWork) {
        if (config.blastProgress() != null && totalWork > 0) {
            var progress = Math.max(0.0, Math.min(1.0, (double) workDone / (double) totalWork));
            Platform.runLater(() -> config.blastProgress().setProgress(progress));
        }
    }

    public void shutdownNow() {
        if (pool != null && !pool.isShutdown() && !pool.isTerminated()) {
            pool.shutdownNow();
        }
    }

    private void runBlast(Path primers, Path query) {
        var messageQueue = new LinkedBlockingQueue<String>();
        var consumer = new MessageConsumer(messageQueue, config.outputField());

        var name = sampleNameFromQuery(query);
        var blastOutput = directories.detailedDir().resolve(name);
        var blastTsv = blastOutput.resolve(name + ".tsv");
        try {
            Files.createDirectories(blastOutput);
            var command = new String[]{AppPaths.executable(dependencies.blastLocation(), "blastn").toString(),
                    "-task", "blastn-short",
                    "-query", query.toString(),
                    "-db", primers.toString(),
                    "-evalue", Double.toString(config.evalue()),
                    "-num_alignments", "1000000",
                    "-num_threads", "1",
                    "-outfmt", "6 qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq",
                    "-out", blastTsv.toString()};

            var process = new ProcessBuilder(command).start();
            processTracker.add(process);
            Platform.runLater(consumer::start);
            streamProcessOutput(process, messageQueue);
            consumer.stop();
            var exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("BLAST failed with exit code " + exitCode + " for query " + query);
            }
            Methods.addHeaderToTSV(blastTsv);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run BLAST for query: " + query, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running BLAST for query: " + query, e);
        }
    }

    private static String sampleNameFromQuery(Path query) {
        var name = query.getFileName().toString();
        for (var suffix : new String[]{"_assembly.fasta", ".fasta", ".fna", ".ffn", ".fa", ".fsa"}) {
            name = name.split(java.util.regex.Pattern.quote(suffix))[0];
        }
        return name;
    }

    private static void streamProcessOutput(Process process, BlockingQueue<String> messageQueue) throws IOException, InterruptedException {
        try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                messageQueue.put(line);
            }
        }
    }
}
