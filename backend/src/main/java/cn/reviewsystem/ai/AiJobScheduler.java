package cn.reviewsystem.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AiJobScheduler {
    private final AiJobWorker worker;
    private final AiProperties properties;
    private final boolean scheduled;

    public AiJobScheduler(
            AiJobWorker worker,
            AiProperties properties,
            @Value("${app.ai.worker-scheduled:true}") boolean scheduled) {
        this.worker = worker;
        this.properties = properties;
        this.scheduled = scheduled;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (scheduled && properties.isConfigured()) {
            worker.drainOnce();
        }
    }
}
