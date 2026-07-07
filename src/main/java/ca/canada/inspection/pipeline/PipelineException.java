package ca.canada.inspection.pipeline;

/** Base unchecked exception for pipeline failures with user-actionable context. */
public class PipelineException extends RuntimeException {
    public PipelineException(String message) {
        super(message);
    }

    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
