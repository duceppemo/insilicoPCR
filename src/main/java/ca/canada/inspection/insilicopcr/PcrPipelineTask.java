package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.pipeline.ExternalProcessTracker;
import ca.canada.inspection.pipeline.InSilicoPcrPipeline;
import ca.canada.inspection.pipeline.LogFiles;
import ca.canada.inspection.pipeline.OutputFolderInUse;
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
import java.nio.file.Path;
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
        // Which run this is, in log.txt, where the runs of a folder follow one another.
        append("Run of " + java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ') + "\nInput: "
                + config.input() + "\nPrimers: " + config.primers() + "\nOutput: " + config.output() + "\n");
        Throwable failure = null;
        try {
            RunResult result = new InSilicoPcrPipeline(config, new WindowMonitor()).run();
            if (isCancelled()) {
                // Stopped after it wrote its log: the run is finished, and its log says so.
                append("\nThe run was stopped after it had finished: its reports and its log (QAlog) are complete.\n");
            }
            return result;
        } catch (RuntimeException | Error e) {
            failure = e;
            if (isCancelled() && stoppedByTheCancel(e)) {
                append("\nRun cancelled\n");
            } else {
                // The console gets the stack trace of a failure.
                e.printStackTrace();
                append("\n" + failureText(e) + "\n");
            }
            throw e;
        } finally {
            if (writesRunLog(config.output(), failure)) {
                writeRunLog();
            }
        }
    }

    /**
     * Whether a run that ended with {@code failure} ({@code null}: none) writes its {@code log.txt} into
     * {@code output}: not before it made the folder, nor into one another run holds, whose log.txt that run may be
     * writing (the refused run's lines went into it).
     */
    static boolean writesRunLog(Path output, Throwable failure) {
        return Files.isDirectory(output) && !(failure instanceof OutputFolderInUse);
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
        // A run of hundreds of FASTQ samples prints some 100,000 lines, and the text area lays out all of
        // its text on each change: the window slowed down, Stop with it. It keeps the latest lines; the
        // run's log.txt keeps them all.
        if (log.getLength() > MAX_SHOWN) {
            log.replaceText(0, cutPoint(log.getText(), MAX_SHOWN, KEPT_SHOWN), TRIMMED_NOTE);
        }
    }

    /** Characters the log area holds at most, and how many of the latest it keeps when it trims. */
    static final int MAX_SHOWN = 1_000_000;
    static final int KEPT_SHOWN = 750_000;
    static final String TRIMMED_NOTE = "[Earlier lines are not shown here; the output folder's log.txt has them all"
            + " once the run ends.]\n";

    /**
     * Where to cut the start of {@code text} to keep its latest {@code kept} characters, at the start of a
     * line, once it holds more than {@code max}; 0 to keep it whole.
     */
    static int cutPoint(CharSequence text, int max, int kept) {
        if (text.length() <= max) {
            return 0;
        }
        int from = text.length() - kept;
        while (from < text.length() && text.charAt(from - 1) != '\n') {
            from++;
        }
        return from;
    }

    private final class WindowMonitor implements RunMonitor {
        // Under the log's lock: the samples' programs write at once, and a line's start was checked by one thread
        // while another wrote.
        private final Consumer<String> programOutput = line -> {
            synchronized (runLog) {
                append("\n" + line);
            }
        };

        @Override
        public void info(String message) {
            // On a line of its own, as on the console: a blank line between every two was the window's alone.
            synchronized (runLog) {
                append((runLog.isEmpty() || runLog.charAt(runLog.length() - 1) == '\n' ? "" : "\n") + message + "\n");
            }
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
