package ca.canada.inspection.pipeline;


import java.util.List;

/** Creates the BLAST nucleotide database used by the PCR search stage. */
public final class BlastDatabaseBuilder {
    private BlastDatabaseBuilder() {
    }

    public static void build(PipelineContext context) {
        if (!AppPaths.isWindows()) {
            ExternalProgram.run(context.monitor(), context.dependencies().blastBinDirectory(),
                    List.of("chmod", "+x", "makeblastdb", "blastn"), "chmod");
        }
        ExternalProgram.run(context.monitor(), null, List.of(
                context.dependencies().makeblastdb().toString(),
                "-dbtype", "nucl",
                "-hash_index",
                "-in", context.directories().primerDatabase().toString()), "makeblastdb");
    }
}
