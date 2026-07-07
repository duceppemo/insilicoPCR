package ca.canada.inspection.pipeline;

public final class DependencyException extends PipelineException {
    public DependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
