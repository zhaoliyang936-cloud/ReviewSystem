package cn.reviewsystem.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiJobService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AiProperties properties;
    private final TransactionTemplate tx;

    @Autowired
    public AiJobService(
            JdbcTemplate db,
            ObjectMapper json,
            AiProperties properties,
            PlatformTransactionManager txManager) {
        this.db = db;
        this.json = json;
        this.properties = properties;
        this.tx = new TransactionTemplate(Objects.requireNonNull(txManager, "txManager"));
    }

    public long createJob(
            long userId,
            String scenario,
            String contextHash,
            String activeKey,
            JsonNode snapshotRefs,
            Long sourceQuestionVersionId,
            Long knowledgeId,
            Long evidenceRevision,
            String state,
            OffsetDateTime deadlineAt) {
        String snapshotJson = writeJson(snapshotRefs);
        String modelVersion = properties.getModel() == null || properties.getModel().isBlank()
                ? "unconfigured"
                : truncate(properties.getModel(), 40);
        Timestamp deadlineTs = deadlineAt == null ? null : Timestamp.from(deadlineAt.toInstant());

        if (activeKey != null) {
            var existing = db.query(
                    "SELECT id FROM rs_ai_job WHERE active_key=? AND state IN ('QUEUED','RUNNING')",
                    (r, n) -> r.getLong(1),
                    activeKey);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            var inserted = db.query(
                    "INSERT INTO rs_ai_job(user_id, subject_id, scenario, context_hash, active_key, "
                            + "snapshot_refs_json, source_question_version_id, knowledge_id, evidence_revision, "
                            + "template_version, model_config_version, state, deadline_at) "
                            + "VALUES (?, 1, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (active_key) WHERE state IN ('QUEUED','RUNNING') DO NOTHING RETURNING id",
                    (r, n) -> r.getLong(1),
                    userId, scenario, contextHash, activeKey, snapshotJson,
                    sourceQuestionVersionId, knowledgeId, evidenceRevision,
                    PromptTemplates.TEMPLATE_VERSION, modelVersion, state, deadlineTs);
            if (!inserted.isEmpty()) {
                return inserted.get(0);
            }
            return Objects.requireNonNull(db.queryForObject(
                    "SELECT id FROM rs_ai_job WHERE active_key=? AND state IN ('QUEUED','RUNNING')",
                    Long.class, activeKey));
        }

        return Objects.requireNonNull(db.queryForObject(
                "INSERT INTO rs_ai_job(user_id, subject_id, scenario, context_hash, active_key, "
                        + "snapshot_refs_json, source_question_version_id, knowledge_id, evidence_revision, "
                        + "template_version, model_config_version, state, deadline_at) "
                        + "VALUES (?, 1, ?, ?, NULL, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                userId, scenario, contextHash, snapshotJson,
                sourceQuestionVersionId, knowledgeId, evidenceRevision,
                PromptTemplates.TEMPLATE_VERSION, modelVersion, state, deadlineTs));
    }

    public void markRunning(long jobId) {
        db.update(
                "UPDATE rs_ai_job SET state='RUNNING', updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='QUEUED'",
                jobId);
    }

    public void succeed(long jobId, JsonNode outputJson, JsonNode usageJson) {
        db.update(
                "UPDATE rs_ai_job SET state='SUCCEEDED', output_json=CAST(? AS jsonb), usage_json=CAST(? AS jsonb), "
                        + "error_code=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                writeNullableJson(outputJson),
                writeNullableJson(usageJson),
                jobId);
    }

    public void fail(long jobId, String errorCode) {
        db.update(
                "UPDATE rs_ai_job SET state='FAILED', error_code=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                truncate(errorCode, 60),
                jobId);
    }

    public void expire(long jobId, String errorCode) {
        db.update(
                "UPDATE rs_ai_job SET state='EXPIRED', error_code=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                truncate(errorCode, 60),
                jobId);
    }

    public int recoverStale(int prepareTimeoutSeconds) {
        int runningStaleSeconds = Math.max(1, prepareTimeoutSeconds) + 120;
        List<Long> candidateIds = db.query(
                "SELECT id FROM rs_ai_job "
                        + "WHERE (state='QUEUED' AND deadline_at IS NOT NULL AND deadline_at < CURRENT_TIMESTAMP) "
                        + "   OR (state='RUNNING' AND updated_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 second')) "
                        + "ORDER BY id LIMIT 50",
                (r, n) -> r.getLong(1),
                runningStaleSeconds);

        int recovered = 0;
        for (long jobId : candidateIds) {
            boolean ok = Boolean.TRUE.equals(tx.execute(status -> processOneStale(jobId, runningStaleSeconds)));
            if (ok) {
                recovered++;
            }
        }
        return recovered;
    }

    private boolean processOneStale(long jobId, int runningStaleSeconds) {
        // 1. Ordinary read without locking rs_ai_job first
        var rows = db.query(
                "SELECT state, snapshot_refs_json::text FROM rs_ai_job "
                        + "WHERE id=? "
                        + "  AND ((state='QUEUED' AND deadline_at IS NOT NULL AND deadline_at < CURRENT_TIMESTAMP) "
                        + "       OR (state='RUNNING' AND updated_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 second')))",
                (r, n) -> new String[]{r.getString(1), r.getString(2)},
                jobId, runningStaleSeconds);
        if (rows.isEmpty()) {
            return false;
        }
        String state = rows.get(0)[0];
        String snapshotRefsJson = rows.get(0)[1];
        String reasonCode = "QUEUED".equals(state) ? "AI_TIMEOUT" : "AI_WORKER_STALE";

        // 2. Lock rs_learning_activity FIRST (activity -> job lock order)
        long activityId = extractActivityId(snapshotRefsJson);
        if (activityId > 0) {
            db.query("SELECT status FROM rs_learning_activity WHERE id=? FOR UPDATE", (r, n) -> r.getString(1), activityId);
        }

        // 3. Lock/Update rs_ai_job SECOND
        int updated = db.update(
                "UPDATE rs_ai_job SET state='EXPIRED', error_code=?, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND state IN ('QUEUED','RUNNING')",
                reasonCode,
                jobId);
        if (updated == 0) {
            return false;
        }

        // 4. Update activity and practice/task within the same transaction
        markActivityPreparationFailed(snapshotRefsJson, reasonCode);
        return true;
    }

    public void markActivityPreparationFailed(String snapshotRefsJson, String reasonCode) {
        JsonNode refs;
        try {
            refs = snapshotRefsJson == null ? null : json.readTree(snapshotRefsJson);
        } catch (Exception e) {
            return;
        }
        if (refs == null || !refs.isObject() || !refs.hasNonNull("activityId") || !refs.get("activityId").canConvertToLong()) {
            return;
        }
        long activityId = refs.get("activityId").asLong();
        db.query("SELECT status FROM rs_learning_activity WHERE id=? FOR UPDATE", (r, n) -> r.getString(1), activityId);
        int actUpdated = db.update(
                "UPDATE rs_learning_activity SET status='PREPARATION_FAILED', updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND status='PREPARING'",
                activityId);
        if (actUpdated == 0) {
            return;
        }

        ObjectNode failure = json.createObjectNode();
        failure.put("reason", reasonCode);
        String failureJson = writeJson(failure);
        String kind = refs.path("kind").asText("PRACTICE");
        if ("TASK".equals(kind) && refs.hasNonNull("taskId") && refs.get("taskId").canConvertToLong()) {
            db.update(
                    "UPDATE rs_review_task SET status='PREPARATION_FAILED', "
                            + "mix_ratio_json = jsonb_set(mix_ratio_json, '{preparationFailure}', CAST(? AS jsonb), true) "
                            + "WHERE id=?",
                    failureJson,
                    refs.get("taskId").asLong());
        } else if (refs.hasNonNull("practiceId") && refs.get("practiceId").canConvertToLong()) {
            db.update(
                    "UPDATE rs_practice SET mix_ratio_json = jsonb_set(mix_ratio_json, '{preparationFailure}', CAST(? AS jsonb), true) "
                            + "WHERE id=?",
                    failureJson,
                    refs.get("practiceId").asLong());
        }
    }

    private long extractActivityId(String snapshotRefsJson) {
        try {
            JsonNode refs = snapshotRefsJson == null ? null : json.readTree(snapshotRefsJson);
            if (refs != null && refs.isObject() && refs.hasNonNull("activityId") && refs.get("activityId").canConvertToLong()) {
                return refs.get("activityId").asLong();
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    public static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return json.writeValueAsString(node == null ? json.createArrayNode() : node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeNullableJson(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
