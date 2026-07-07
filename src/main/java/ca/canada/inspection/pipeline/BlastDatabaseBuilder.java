package ca.canada.inspection.pipeline;

import ca.canada.inspection.commandpcr.CommandMethods;
import ca.canada.inspection.dispatchpcr.AppPaths;

public final class BlastDatabaseBuilder {
    public void build(PipelineContext context) {
        if (!AppPaths.isWindows()) {
            CommandMethods.makeExecutable(context.dependencies().blastBinDirectory());
        }
        CommandMethods.makeBlastDB(context.directories().primerDatabase(), context.dependencies().blastBinDirectory());
    }
}
