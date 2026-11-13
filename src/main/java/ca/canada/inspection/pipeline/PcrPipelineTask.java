package ca.canada.inspection.pipeline;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A run started from the application: the command line's run ({@link InSilicoPcrPipeline}),
 * reporting to the window's log and progress bars, and stopped when the window closes.
 */
public final class PcrPipelineTask extends Task<Void> {
    private final PipelineConfig config;
    private final TextArea log;
    private final ProgressBar blastProgress;
    private final ExternalProcessTracker processTracker;
    /** Text waiting to be added to the log, so the window gets one update per frame, not one per line. */
    private final Queue<String> pendingLog = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean logUpdateScheduled = new AtomicBoolean();

    public PcrPipelineTask(PipelineConfig config, TextArea log, ProgressBar blastProgress,
                           ExternalProcessTracker processTracker) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.blastProgress = blastProgress;
        this.processTracker = Objects.requireNonNull(processTracker, "processTracker");
    }

    @Override
    protected Void call() {
        new InSilicoPcrPipeline(config, new WindowMonitor()).run();
        return null;
    }

    /** Kills the external programs still running; call after {@link #cancel(boolean)}. */
    public void shutdownNow() {
        processTracker.destroyAll();
    }

    private void append(String text) {
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
