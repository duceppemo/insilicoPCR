package ca.canada.inspection.pipeline;

/** An external program that ended with a non-zero exit code. */
public final class ProgramFailedException extends PipelineException {
    private final int exitCode;

    public ProgramFailedException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
