package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.util.ProcessRunner;

/** Creates the BLAST nucleotide database used by the PCR search stage. */
public final class BlastDatabaseBuilder {
    public void build(PipelineContext context) {
        if (!AppPaths.isWindows()) {
            ProcessRunner.run(context.dependencies().blastBinDirectory(), "chmod", "+x", "makeblastdb", "blastn");
        }

        ProcessRunner.run(null,
                context.dependencies().makeblastdb().toString(),
                "-dbtype", "nucl",
                "-hash_index",
                "-in", context.directories().primerDatabase().toString());
    }
}
