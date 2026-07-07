package ca.canada.inspection.pipeline;

public final class StageException extends PipelineException {
    public StageException(String message, Throwable cause) {
        super(message, cause);
    }
}
