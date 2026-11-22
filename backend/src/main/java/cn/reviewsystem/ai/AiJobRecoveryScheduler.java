package cn.reviewsystem.ai;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiJobRecoveryScheduler {
    private final AiJobService jobService;
    private final AiProperties properties;

    public AiJobRecoveryScheduler(AiJobService jobService, AiProperties properties) {
        this.jobService = jobService;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 15000)
    public void recover() {
        jobService.recoverStale(properties.getPrepareTimeoutSeconds());
    }
}
