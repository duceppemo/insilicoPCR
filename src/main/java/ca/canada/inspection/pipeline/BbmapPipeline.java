package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.MessageConsumer;
import ca.canada.inspection.insilicopcr.Sample;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class BbmapPipeline {
    private final PcrRunConfig config;
    private final RunDirectories directories;
    private final DependencyContext dependencies;
    private final ExternalProcessTracker processTracker;
    private final Map<String, String> primerDict;
    private final Map<String, Sample> sampleDict;

    public BbmapPipeline(PcrRunConfig config,
                         RunDirectories directories,
                         DependencyContext dependencies,
                         ExternalProcessTracker processTracker,
                         Map<String, String> primerDict,
                         Map<String, Sample> sampleDict) {
        this.config = config;
        this.directories = directories;
        this.dependencies = dependencies;
        this.processTracker = processTracker;
        this.primerDict = primerDict;
        this.sampleDict = sampleDict;
    }

    public void runFirstBait(Task<?> owner) {
        var kLength = shortestPrimerLength();
        var ref = config.outDir().resolve("primer_tmp.fasta").toString();

        for (var sample : sampleDict.values()) {
            stopIfCancelled(owner);
            if (!"fastq".equals(sample.getFileType())) {
                continue;
            }

            var sampleDir = sampleDirectory(sample);
            var files = sample.getFiles();
            String[] command;
            if (files.size() == 2) {
                command = new String[]{dependencies.javaCommand(), "-ea", "-Xmx7g", "-cp", dependencies.bbtoolsJar().toString(), "jgi.BBDuk",
                        "ref=" + ref, "k=" + kLength,
                        "in1=" + files.get(0), "in2=" + files.get(1), "hdist=" + config.mismatches(),
                        "threads=" + config.threads(), "interleaved=t",
                        "outm=" + sampleDir.resolve(sample.getName() + "_targetMatches.fastq.gz")};
            } else {
                command = new String[]{dependencies.javaCommand(), "-ea", "-Xmx7g", "-cp", dependencies.bbtoolsJar().toString(), "jgi.BBDuk",
                        "ref=" + ref, "k=" + kLength,
                        "in=" + files.getFirst(), "hdist=" + config.mismatches(), "threads=" + config.threads(),
                        "interleaved=t", "outm=" + sampleDir.resolve(sample.getName() + "_targetMatches.fastq.gz")};
            }
            runProcess(command, dependencies.bbtoolsLocation(), owner);
        }
        processTracker.clear();
    }

    public void runSecondBait(Task<?> owner) {
        for (var sample : sampleDict.values()) {
            stopIfCancelled(owner);
            if (!"fastq".equals(sample.getFileType())) {
                continue;
            }

            var sampleDir = sampleDirectory(sample);
            var ref = sampleDir.resolve(sample.getName() + "_targetMatches.fastq.gz").toString();
            var files = sample.getFiles();
            String[] command;
            if (files.size() == 2) {
                command = new String[]{dependencies.javaCommand(), "-ea", "-Xmx7g", "-cp", dependencies.bbtoolsJar().toString(), "jgi.BBDuk",
                        "ref=" + ref, "in1=" + files.get(0), "in2=" + files.get(1),
                        "hdist=" + config.mismatches(), "threads=" + config.threads(), "interleaved=t",
                        "outm=" + sampleDir.resolve(sample.getName() + "_doubleTargetMatches.fastq.gz")};
            } else {
                command = new String[]{dependencies.javaCommand(), "-ea", "-Xmx7g", "-cp", dependencies.bbtoolsJar().toString(), "jgi.BBDuk",
                        "ref=" + ref, "in=" + files.getFirst(), "hdist=" + config.mismatches(),
                        "threads=" + config.threads(), "interleaved=t",
                        "outm=" + sampleDir.resolve(sample.getName() + "_doubleTargetMatches.fastq.gz")};
            }
            runProcess(command, dependencies.bbtoolsLocation(), owner);
        }
        processTracker.clear();
    }

    public void runAssembly(Task<?> owner) {
        for (var sample : sampleDict.values()) {
            stopIfCancelled(owner);
            if (!"fastq".equals(sample.getFileType())) {
                continue;
            }

            var sampleDir = sampleDirectory(sample);
            var in = sampleDir.resolve(sample.getName() + "_doubleTargetMatches.fastq.gz");
            var out = sampleDir.resolve(sample.getName() + "_assembly.fasta");
            sample.setAssemblyFile(out);
            var command = new String[]{dependencies.javaCommand(), "-ea", "-Xmx7g", "-cp", dependencies.bbtoolsJar().toString(), "assemble.Tadpole",
                    "in=" + in, "out=" + out, "threads=" + config.threads()};
            runProcess(command, dependencies.bbtoolsLocation(), owner);
        }
        processTracker.clear();
    }

    private int shortestPrimerLength() {
        return primerDict.values().stream().mapToInt(String::length).min().orElseThrow();
    }

    private Path sampleDirectory(Sample sample) {
        var sampleDir = directories.detailedDir().resolve(sample.getName());
        try {
            Files.createDirectories(sampleDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create sample directory: " + sampleDir, e);
        }
        return sampleDir;
    }

    private void runProcess(String[] command, Path workingDirectory, Task<?> owner) {
        stopIfCancelled(owner);

        var messageQueue = new LinkedBlockingQueue<String>();
        var consumer = new MessageConsumer(messageQueue, config.outputField());
        Process process = null;

        try {
            process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();

            processTracker.add(process);
            Platform.runLater(consumer::start);
            streamProcessOutput(process, messageQueue, owner);
            consumer.stop();

            var exitCode = process.waitFor();
            stopIfCancelled(owner);
            if (exitCode != 0) {
                throw new IllegalStateException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start command: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("Interrupted while running command: " + String.join(" ", command), e);
        }
    }

    private static void streamProcessOutput(Process process,
                                            BlockingQueue<String> messageQueue,
                                            Task<?> owner) throws IOException, InterruptedException {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stopIfCancelled(owner);
                messageQueue.put(line);
            }
        }
    }

    private static void stopIfCancelled(Task<?> owner) {
        if (owner != null && owner.isCancelled()) {
            throw new IllegalStateException("BBMap pipeline was cancelled.");
        }
    }
}
