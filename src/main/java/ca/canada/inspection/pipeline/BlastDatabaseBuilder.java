package ca.canada.inspection.pipeline;


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Creates the BLAST nucleotide database used by the PCR search stage. */
public final class BlastDatabaseBuilder {
    private BlastDatabaseBuilder() {
    }

    public static void build(PipelineContext context) {
        makeExecutable(context, context.dependencies().makeblastdb(), context.dependencies().blastn());
        // BLAST+ splits the -in path at its spaces itself: run in the database's folder with its bare name.
        Path database = context.directories().primerDatabase().toAbsolutePath();
        long started = System.currentTimeMillis();
        try {
            context.monitor().runProgram(database.getParent(), List.of(
                    context.dependencies().makeblastdb().toString(),
                    "-dbtype", "nucl",
                    "-hash_index",
                    "-in", database.getFileName().toString(),
                    // Without -out, makeblastdb names the database after the absolute path of -in.
                    "-out", database.getFileName().toString()), "makeblastdb");
        } catch (ProgramFailedException e) {
            // In a folder whose path has a space, makeblastdb writes the database, then checks it by its
            // absolute path, which it splits at the space: the check fails, not the database, which
            // blastn opens by its bare name. A database that is in fact broken stops blastn at once.
            if (!database.toString().contains(" ") || !written(database, started)) {
                throw e;
            }
            context.monitor().info("Note: makeblastdb's own check of the primer database fails in a folder whose"
                    + " path has a space (its error above); the database is complete and is used.");
        }
    }

    /** Whether makeblastdb wrote the index, sequence and header files of {@code database} in this run. */
    static boolean written(Path database, long since) {
        for (String extension : List.of(".nin", ".nsq", ".nhr")) {
            Path file = database.resolveSibling(database.getFileName() + extension);
            try {
                if (!Files.isRegularFile(file) || Files.getLastModifiedTime(file).toMillis() < since - 2_000) {
                    return false;
                }
            } catch (java.io.IOException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Makes the bundled BLAST+ programs executable when an archive tool lost the mode; the release
     * sets it. Only tried when needed, and a failure is a warning: a user who does not own a
     * shared installation cannot change the mode, and need not.
     */
    private static void makeExecutable(PipelineContext context, Path... programs) {
        if (AppPaths.isWindows()) {
            return;
        }
        for (Path program : programs) {
            if (Files.isExecutable(program)) {
                continue;
            }
            try {
                context.monitor().runProgram(null, List.of("chmod", "+x", program.toString()), "chmod");
            } catch (PipelineException e) {
                context.monitor().warning("Warning: " + program + " is not executable and could not be made so: "
                        + e.getMessage());
            }
        }
    }
}
