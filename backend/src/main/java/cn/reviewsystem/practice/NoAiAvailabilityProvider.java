package cn.reviewsystem.practice;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "false", matchIfMissing = true)
public class NoAiAvailabilityProvider implements AiAvailabilityPort {
    @Override
    public AiStatus status(long userId) {
        return AiStatus.no("AI_DISABLED");
    }
}
