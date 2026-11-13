package ca.canada.inspection.pipeline;

import java.util.function.Consumer;

/**
 * Where a run reports what it does, and how it is stopped. The command line uses
 * {@link #CONSOLE}; the application sends everything to its window and stops the run
 * when the window closes.
 */
public interface RunMonitor {

    /** Messages to standard output, warnings to standard error, programs on the console. */
    RunMonitor CONSOLE = new RunMonitor() {
    };

    /** The steps of a run, in order, for a progress bar. */
    enum Step {
        DEPENDENCIES, INPUT_CHECK, PRIMERS, READS, BLAST_DATABASE, BLAST, READ_SEARCH, READ_EXTENSION, REPORTS;

        /** Share of the run done once this step is done, from 0 to 1. */
        public double doneFraction() {
            return (ordinal() + 1) / (double) values().length;
        }
    }

    /** A progress message. */
    default void info(String message) {
        System.out.println(message);
    }

    /** A message about something that went wrong but did not stop the run. */
    default void warning(String message) {
        System.err.println(message);
    }

    /**
     * Receives what external programs print, line by line; {@code null} lets them write
     * to the console.
     */
    default Consumer<String> programOutput() {
        return null;
    }

    /** Tracks the external programs so they can be killed; {@code null} when nothing kills them. */
    default ExternalProcessTracker processes() {
        return null;
    }

    /** Whether the run was cancelled; checked before each program starts and after it ends. */
    default boolean isCancelled() {
        return false;
    }

    /** Called when {@code step} is done. */
    default void stepDone(Step step) {
    }

    /** Called as the BLAST searches of the samples finish. */
    default void blastProgress(int done, int total) {
    }
}
