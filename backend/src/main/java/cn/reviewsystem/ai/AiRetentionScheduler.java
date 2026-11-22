package cn.reviewsystem.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiRetentionScheduler {
    private final JdbcTemplate db;

    public AiRetentionScheduler(JdbcTemplate db) {
        this.db = db;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void scheduledPurge() {
        purgeExpiredOutputs();
    }

    public int purgeExpiredOutputs() {
        return db.update(
                "UPDATE rs_ai_job "
                        + "SET output_json = NULL, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id IN ("
                        + "  SELECT id FROM rs_ai_job "
                        + "  WHERE scenario IN ('GUIDANCE', 'EXPLANATION', 'SUBJECTIVE_REVIEW') "
                        + "    AND output_json IS NOT NULL "
                        + "    AND created_at < CURRENT_TIMESTAMP - INTERVAL '30 days' "
                        + "  ORDER BY created_at ASC, id ASC "
                        + "  LIMIT 500"
                        + ")");
    }
}
