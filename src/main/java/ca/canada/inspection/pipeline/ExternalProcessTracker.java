package ca.canada.inspection.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * The external processes a run started, so that they can be killed with it: when the
 * application's window closes or its run is stopped, and when the command line is killed
 * (a shutdown hook). After {@link #destroyAll()} the tracker stays shut: a process
 * registered later (one that was starting meanwhile) is killed at once instead of being
 * left running.
 */
public final class ExternalProcessTracker {
    private final List<Process> processes = new ArrayList<>();
    private boolean shutDown;

    /** Registers {@code process}, or kills it right away if the tracker was shut down. */
    public synchronized void add(Process process) {
        if (shutDown) {
            process.destroyForcibly();
            return;
        }
        processes.add(process);
    }

    /** Forgets {@code process}, which has ended. */
    public synchronized void remove(Process process) {
        processes.remove(process);
    }

    /** Whether {@link #destroyAll()} was called: the run is being stopped. */
    public synchronized boolean isShutDown() {
        return shutDown;
    }

    /** Kills every registered process and every process registered from now on. */
    public synchronized void destroyAll() {
        shutDown = true;
        for (var process : processes) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        processes.clear();
    }
}
