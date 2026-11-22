package cn.reviewsystem.ai;

public class AiUnavailableException extends RuntimeException {
    private final AiFailure failure;

    public AiUnavailableException(AiFailure failure, String safeMessage) {
        super(safeMessage);
        this.failure = failure;
    }

    public AiUnavailableException(AiFailure failure, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.failure = failure;
    }

    public AiFailure getFailure() {
        return failure;
    }
}
