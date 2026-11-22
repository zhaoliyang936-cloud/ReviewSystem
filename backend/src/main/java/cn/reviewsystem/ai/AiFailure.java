package cn.reviewsystem.ai;

public enum AiFailure {
    DISABLED,
    UNCONFIGURED,
    QUOTA_EXCEEDED,
    RATE_LIMITED,
    SERVER_ERROR,
    AUTH,
    BAD_REQUEST,
    TIMEOUT,
    UNREACHABLE,
    MALFORMED
}
