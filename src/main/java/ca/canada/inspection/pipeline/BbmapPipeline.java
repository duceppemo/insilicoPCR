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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * GUI counterpart of {@link FastqProcessor}: same BBDuk/Tadpole commands (from
 * {@link BbtoolsCommands}), run one sample at a time with cancellation support
 * and output streamed to the GUI log.
 */
public final class BbmapPipeline {
    private final PcrRunConfig config;
    private final RunDirectories directories;
    private final DependencyContext dependencies;
    private final ExternalProcessTracker processTracker;
    private final Map<String, String> primerDict;
    private final Map<String, Sample> sampleDict;
    private final Path primerDatabase;
    private final BbtoolsCommands commands;

    /**
     * @param primerDict     expanded primers (as returned by primer processing)
     * @param primerDatabase primer FASTA written for this run, used as the first-pass bait
     */
    public BbmapPipeline(PcrRunConfig config,
                         RunDirectories directories,
                         DependencyContext dependencies,
                         ExternalProcessTracker processTracker,
                         Map<String, String> primerDict,
                         Map<String, Sample> sampleDict,
                         Path primerDatabase) {
        this.config = config;
        this.directories = directories;
        this.dependencies = dependencies;
        this.processTracker = processTracker;
        this.primerDict = primerDict;
        this.sampleDict = sampleDict;
        this.primerDatabase = primerDatabase;
        this.commands = new BbtoolsCommands(dependencies.javaCommand(), dependencies.bbtoolsJar(),
                ChildJavaMemory.recommendedGiB(), config.threads());
    }

    public void runFirstBait(Task<?> owner) {
        var kLength = shortestPrimerLength();
        for (var sample : fastqSamples()) {
            stopIfCancelled(owner);
            var sampleDir = sampleDirectory(sample);
            runProcess(commands.firstBait(primerDatabase, sample.files(),
                    BbtoolsCommands.targetMatches(sampleDir, sample.getName()), kLength, config.mismatches()), owner);
        }
        processTracker.clear();
    }

    public void runSecondBait(Task<?> owner) {
        for (var sample : fastqSamples()) {
            stopIfCancelled(owner);
            var sampleDir = sampleDirectory(sample);
            runProcess(commands.secondBait(BbtoolsCommands.targetMatches(sampleDir, sample.getName()), sample.files(),
                    BbtoolsCommands.doubleTargetMatches(sampleDir, sample.getName())), owner);
        }
        processTracker.clear();
    }

    public void runAssembly(Task<?> owner) {
        for (var sample : fastqSamples()) {
            stopIfCancelled(owner);
            var sampleDir = sampleDirectory(sample);
            var out = BbtoolsCommands.assembly(sampleDir, sample.getName());
            sample.setAssemblyFile(out);
            runProcess(commands.assemble(BbtoolsCommands.doubleTargetMatches(sampleDir, sample.getName()), out), owner);
        }
        processTracker.clear();
    }

    private List<Sample> fastqSamples() {
        return sampleDict.values().stream()
                .filter(Sample::isFastq)
                .sorted(Comparator.comparing(Sample::getName))
                .toList();
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

    private void runProcess(List<String> command, Task<?> owner) {
        stopIfCancelled(owner);
        var workingDirectory = dependencies.bbtoolsLocation();

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
        } catch (CancellationException e) {
            destroy(process);
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start command: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy(process);
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
            throw new CancellationException("BBMap pipeline was cancelled.");
        }
    }

    private static void destroy(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
