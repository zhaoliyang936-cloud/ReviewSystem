package cn.reviewsystem.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** app.ai.scheduler.enabled=false 时作业轮询与卡死恢复都会停摆，启动即告警。 */
@Component
public class AiSchedulerSwitchWarning {
    private static final Logger log = LoggerFactory.getLogger(AiSchedulerSwitchWarning.class);

    public AiSchedulerSwitchWarning(@Value("${app.ai.scheduler.enabled:true}") boolean enabled) {
        if (!enabled) {
            log.warn("app.ai.scheduler.enabled=false: AI 作业轮询与 RUNNING 卡死恢复已关闭，"
                    + "QUEUED/RUNNING 作业不会推进；仅测试环境可关闭");
        }
    }
}
