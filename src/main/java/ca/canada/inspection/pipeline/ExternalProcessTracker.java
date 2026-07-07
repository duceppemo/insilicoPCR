package ca.canada.inspection.pipeline;

import java.util.ArrayList;
import java.util.List;

public final class ExternalProcessTracker {
    private final List<Process> processes = new ArrayList<>();

    public synchronized void add(Process process) {
        processes.add(process);
    }

    public synchronized void clear() {
        processes.clear();
    }

    public synchronized void destroyAll() {
        for (var process : processes) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        processes.clear();
    }
}
