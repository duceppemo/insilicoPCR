package ca.canada.inspection.pipeline;

import java.nio.file.Path;

/**
 * Another run holds the output folder ({@link OutputLock}): this run did nothing in it, and writes nothing there,
 * not even the window's {@code log.txt}, which the other run may be writing.
 */
public final class OutputFolderInUse extends PipelineException {
    public OutputFolderInUse(Path output) {
        super("Another run is using the output folder " + output
                + ": give each run its own output folder (a workflow manager running one job per sample, one"
                + " folder per job), or wait for that run to finish");
    }
}
