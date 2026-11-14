package ca.canada.inspection.pipeline;

/** An external program that ended with a non-zero exit code. */
public final class ProgramFailedException extends PipelineException {
    private final String program;
    private final int exitCode;

    public ProgramFailedException(String program, int exitCode, String message) {
        super(message);
        this.program = program;
        this.exitCode = exitCode;
    }

    /** Name of the program, as in the message. */
    public String program() {
        return program;
    }

    public int exitCode() {
        return exitCode;
    }
}
