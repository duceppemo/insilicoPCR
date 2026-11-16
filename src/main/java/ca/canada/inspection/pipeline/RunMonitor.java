package ca.canada.inspection.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Where a run reports what it does, how it runs its external programs, and how it is stopped.
 * The command line uses the console ({@code Dispatcher.consoleMonitor()}: {@link #CONSOLE} with its
 * external programs tracked, and stopped with it); the application sends everything to its window
 * and stops the run when the window closes.
 */
public interface RunMonitor {

    /** Messages to standard output, warnings to standard error, programs on the console. */
    RunMonitor CONSOLE = new RunMonitor() {
    };

    /** The steps of a run, in order, for a progress bar. */
    enum Step {
        DEPENDENCIES, PRIMERS, INPUT_CHECK, BLAST_DATABASE, READS, BLAST, READ_SEARCH, READ_EXTENSION, REPORTS;

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
     * to the console (their error output through insilicoPCR, unchanged, which reads it
     * for a program saying it ran out of memory).
     */
    default Consumer<String> programOutput() {
        return null;
    }

    /**
     * Runs an external program ({@link ExternalProgram#run}): every program of a run is started
     * here, so a test can stand in for one (a program that fails, or runs out of memory).
     *
     * @param directory where it runs, or {@code null} for the current directory
     * @throws ProgramFailedException when it exits with an error
     */
    default void runProgram(Path directory, List<String> command, String program) {
        ExternalProgram.run(this, directory, command, program);
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
