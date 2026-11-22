package cn.reviewsystem.ai;

import java.time.Duration;

public interface LlmClient {
    LlmResponse complete(LlmRequest req, Duration timeout);
}
