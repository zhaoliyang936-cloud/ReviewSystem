package cn.reviewsystem.ai;

import cn.reviewsystem.practice.AiAvailabilityPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class AiAvailability implements AiAvailabilityPort {
    private final AiProperties properties;
    private final AiQuotaService quotaService;

    public AiAvailability(AiProperties properties, AiQuotaService quotaService) {
        this.properties = properties;
        this.quotaService = quotaService;
    }

    @Override
    public AiStatus status(long userId) {
        if (!properties.isConfigured()) {
            return AiStatus.no("AI_DISABLED");
        }
        if (!quotaService.hasRemainingQuota(userId)) {
            return AiStatus.no("AI_QUOTA_EXCEEDED");
        }
        return AiStatus.ok();
    }
}
