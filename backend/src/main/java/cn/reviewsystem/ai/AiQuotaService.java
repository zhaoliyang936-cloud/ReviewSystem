package cn.reviewsystem.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AiQuotaService {
    private final JdbcTemplate db;
    private final AiProperties properties;

    public AiQuotaService(JdbcTemplate db, AiProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    public boolean hasRemainingQuota(long userId) {
        int limit = properties.getDailyCallLimit();
        if (limit <= 0) {
            return false;
        }
        var rows = db.query(
                "SELECT used_calls FROM rs_ai_quota WHERE scope_key=? AND budget_date=CURRENT_DATE",
                (r, n) -> r.getInt(1),
                scopeKey(userId));
        return rows.isEmpty() || rows.get(0) < limit;
    }

    public boolean tryConsume(long userId) {
        int limit = properties.getDailyCallLimit();
        if (limit <= 0) {
            return false;
        }
        var rows = db.query(
                "INSERT INTO rs_ai_quota(scope_key, budget_date, used_calls) VALUES (?, CURRENT_DATE, 1) "
                        + "ON CONFLICT (scope_key, budget_date) DO UPDATE "
                        + "SET used_calls = rs_ai_quota.used_calls + 1 "
                        + "WHERE rs_ai_quota.used_calls < ? "
                        + "RETURNING used_calls",
                (r, n) -> r.getInt(1),
                scopeKey(userId),
                limit);
        return !rows.isEmpty();
    }

    private static String scopeKey(long userId) {
        return "user:" + userId;
    }
}
