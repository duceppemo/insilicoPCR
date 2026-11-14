package ca.canada.inspection.pipeline;

/** An external program that ended with a non-zero exit code. */
public final class ProgramFailedException extends PipelineException {
    private final String program;
    private final int exitCode;
    private final boolean saidOutOfMemory;

    /** @param saidOutOfMemory the program printed that it ran out of memory */
    public ProgramFailedException(String program, int exitCode, boolean saidOutOfMemory, String message) {
        super(message);
        this.program = program;
        this.exitCode = exitCode;
        this.saidOutOfMemory = saidOutOfMemory;
    }

    /** Name of the program, as in the message. */
    public String program() {
        return program;
    }

    public int exitCode() {
        return exitCode;
    }

    /** The program printed that it ran out of memory (BBTools does, before it stops with exit code 1). */
    public boolean saidOutOfMemory() {
        return saidOutOfMemory;
    }
}
