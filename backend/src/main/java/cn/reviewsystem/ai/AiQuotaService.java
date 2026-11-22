package cn.reviewsystem.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AiQuotaService {
    public enum Bucket {
        GENERATED_OR_REVIEWED,
        MASTERY,
        TUTORING
    }

    private final JdbcTemplate db;
    private final AiProperties properties;

    public AiQuotaService(JdbcTemplate db, AiProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    public boolean hasRemainingQuota(long userId) {
        return hasRemainingQuota(userId, Bucket.GENERATED_OR_REVIEWED);
    }

    public boolean hasRemainingQuota(long userId, Bucket bucket) {
        int totalLimit = properties.getDailyCallLimit();
        int bucketLimit = limitFor(bucket);
        if (totalLimit <= 0 || bucketLimit <= 0) {
            return false;
        }
        return queryUsed(totalScopeKey(userId)) < totalLimit
                && queryUsed(bucketScopeKey(userId, bucket)) < bucketLimit;
    }

    public boolean tryConsume(long userId) {
        return tryConsume(userId, Bucket.GENERATED_OR_REVIEWED);
    }

    public boolean tryConsume(long userId, Bucket bucket) {
        int totalLimit = properties.getDailyCallLimit();
        int bucketLimit = limitFor(bucket);
        if (totalLimit <= 0 || bucketLimit <= 0) {
            return false;
        }
        String bKey = bucketScopeKey(userId, bucket);
        if (!atomicIncrement(bKey, bucketLimit)) {
            return false;
        }
        String tKey = totalScopeKey(userId);
        if (!atomicIncrement(tKey, totalLimit)) {
            db.update(
                    "UPDATE rs_ai_quota SET used_calls = GREATEST(0, used_calls - 1) WHERE scope_key=? AND budget_date=CURRENT_DATE",
                    bKey);
            return false;
        }
        return true;
    }

    public int limitFor(Bucket bucket) {
        AiProperties.Quota q = properties.getQuota();
        if (q == null) {
            return properties.getDailyCallLimit();
        }
        return switch (bucket) {
            case GENERATED_OR_REVIEWED -> q.getGenerationDaily();
            case MASTERY -> q.getMasteryDaily();
            case TUTORING -> q.getTutoringDaily();
        };
    }

    private int queryUsed(String key) {
        var rows = db.query(
                "SELECT used_calls FROM rs_ai_quota WHERE scope_key=? AND budget_date=CURRENT_DATE",
                (r, n) -> r.getInt(1),
                key);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private boolean atomicIncrement(String key, int limit) {
        var rows = db.query(
                "INSERT INTO rs_ai_quota(scope_key, budget_date, used_calls) VALUES (?, CURRENT_DATE, 1) "
                        + "ON CONFLICT (scope_key, budget_date) DO UPDATE "
                        + "SET used_calls = rs_ai_quota.used_calls + 1 "
                        + "WHERE rs_ai_quota.used_calls < ? "
                        + "RETURNING used_calls",
                (r, n) -> r.getInt(1),
                key,
                limit);
        return !rows.isEmpty();
    }

    public static String totalScopeKey(long userId) {
        return "user:" + userId;
    }

    public static String bucketScopeKey(long userId, Bucket bucket) {
        return "user:" + userId + ":" + bucket.name();
    }
}
