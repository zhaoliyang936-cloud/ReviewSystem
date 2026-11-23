package cn.reviewsystem.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AiJobRecoveryScheduler {
    private final AiJobService jobService;
    private final AiProperties properties;

    public AiJobRecoveryScheduler(AiJobService jobService, AiProperties properties) {
        this.jobService = jobService;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 15000)
    public void recover() {
        sweep();
    }

    public int sweep() {
        return jobService.recoverStale(properties.getPrepareTimeoutSeconds());
    }
}
