package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.pipeline.ExternalProcessTracker;
import ca.canada.inspection.pipeline.InSilicoPcrPipeline;
import ca.canada.inspection.pipeline.LogFiles;
import ca.canada.inspection.pipeline.PipelineConfig;
import ca.canada.inspection.pipeline.RunFailure;
import ca.canada.inspection.pipeline.RunMonitor;
import ca.canada.inspection.pipeline.RunResult;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A run started from the application: the command line's run ({@link InSilicoPcrPipeline}),
 * reporting to the window's log and progress bars.
 *
 * <p>Whatever its end (done, failed, cancelled), the run appends what it showed to
 * {@code log.txt} in its output folder before the task ends. {@link #cancel(boolean)} also
 * kills the external programs the run started.</p>
 */
final class PcrPipelineTask extends Task<RunResult> {
    private final PipelineConfig config;
    private final TextArea log;
    private final ProgressBar blastProgress;
    /** This run's programs only: a later run in the same window has its own. */
    private final ExternalProcessTracker processTracker = new ExternalProcessTracker();
    /** Text waiting to be added to the log, so the window gets one update per frame, not one per line. */
    private final Queue<String> pendingLog = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean logUpdateScheduled = new AtomicBoolean();
    /** Everything this run showed, for its log.txt. */
    private final StringBuffer runLog = new StringBuffer();

    PcrPipelineTask(PipelineConfig config, TextArea log, ProgressBar blastProgress) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.blastProgress = blastProgress;
    }

    @Override
    protected RunResult call() {
        try {
            RunResult result = new InSilicoPcrPipeline(config, new WindowMonitor()).run();
            if (isCancelled()) {
                // Stopped after it wrote QAlog.txt: the run is finished, and its log says so.
                append("\nThe run was stopped after it had finished: its reports and QAlog.txt are complete.\n");
            }
            return result;
        } catch (RuntimeException | Error e) {
            if (isCancelled() && stoppedByTheCancel(e)) {
                append("\nRun cancelled\n");
            } else {
                // The console gets the stack trace of a failure.
                e.printStackTrace();
                append("\n" + failureText(e) + "\n");
            }
            throw e;
        } finally {
            writeRunLog();
        }
    }

    /**
     * Whether {@code error} is the cancel itself (a stopped step, an interrupted wait), not a
     * failure that happened to come after it: that one keeps its stack trace.
     */
    private static boolean stoppedByTheCancel(Throwable error) {
        for (Throwable link = error; link != null; link = link.getCause()) {
            if (link instanceof java.util.concurrent.CancellationException || link instanceof InterruptedException
                    || link instanceof java.nio.channels.ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    /** The message of a failure the user can act on; the stack trace of anything else (a bug). */
    private static String failureText(Throwable error) {
        if (RunFailure.isForTheUser(error)) {
            return "Run failed\n" + String.join("\n", RunFailure.lines(error));
        }
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        return "Run failed:\n" + trace;
    }

    private void writeRunLog() {
        if (!Files.isDirectory(config.output())) {
            return; // the run stopped before making its output folder
        }
        try {
            LogFiles.appendRunLog(config.output(), runLog.toString().strip());
        } catch (RuntimeException e) {
            append("\nUnable to write log.txt: " + e.getMessage() + "\n");
        }
    }

    /** Cancels the run and kills the external programs it is running or about to start. */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        // Cancelled first, so no step starts a new program after the running ones are killed.
        boolean cancelled = super.cancel(mayInterruptIfRunning);
        processTracker.destroyAll();
        return cancelled;
    }

    private void append(String text) {
        runLog.append(text);
        pendingLog.add(text);
        if (logUpdateScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushLog);
        }
    }

    private void flushLog() {
        // Cleared first: text added while this runs schedules the next update.
        logUpdateScheduled.set(false);
        StringBuilder text = new StringBuilder();
        for (String next; (next = pendingLog.poll()) != null; ) {
            text.append(next);
        }
        log.appendText(text.toString());
    }

    private final class WindowMonitor implements RunMonitor {
        private final Consumer<String> programOutput = line -> append("\n" + line);

        @Override
        public void info(String message) {
            append("\n" + message + "\n");
        }

        /** Warnings and notes start with {@code Warning:} or {@code Note:}; the window shows them as they are. */
        @Override
        public void warning(String message) {
            info(message);
        }

        @Override
        public Consumer<String> programOutput() {
            return programOutput;
        }

        @Override
        public ExternalProcessTracker processes() {
            return processTracker;
        }

        @Override
        public boolean isCancelled() {
            return PcrPipelineTask.this.isCancelled();
        }

        @Override
        public void stepDone(Step step) {
            updateProgress(step.doneFraction(), 1.0);
        }

        @Override
        public void blastProgress(int done, int total) {
            if (blastProgress != null && total > 0) {
                double fraction = Math.clamp((double) done / total, 0.0, 1.0);
                Platform.runLater(() -> blastProgress.setProgress(fraction));
            }
        }
    }
}
