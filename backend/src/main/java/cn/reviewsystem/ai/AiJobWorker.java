package cn.reviewsystem.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AiJobWorker {
    private static final Logger log = LoggerFactory.getLogger(AiJobWorker.class);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AiJobService jobService;
    private final QuestionGenerationService questionGenerator;
    private final TransactionTemplate tx;

    public AiJobWorker(
            JdbcTemplate db,
            ObjectMapper json,
            AiJobService jobService,
            QuestionGenerationService questionGenerator,
            PlatformTransactionManager txManager) {
        this.db = db;
        this.json = json;
        this.jobService = jobService;
        this.questionGenerator = questionGenerator;
        this.tx = new TransactionTemplate(txManager);
    }

    public int drainOnce() {
        int processed = 0;
        while (processNext()) {
            processed++;
        }
        return processed;
    }

    public boolean processNext() {
        QueuedJob job = tx.execute(status -> {
            var rows = db.query(
                    "SELECT id, user_id, snapshot_refs_json::text, source_question_version_id, knowledge_id, deadline_at "
                            + "FROM rs_ai_job "
                            + "WHERE state='QUEUED' AND scenario='QUESTION_GENERATION' "
                            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                    (r, n) -> {
                        Timestamp ts = r.getTimestamp(6);
                        return new QueuedJob(
                                r.getLong(1),
                                r.getLong(2),
                                r.getString(3),
                                r.getObject(4, Long.class),
                                r.getObject(5, Long.class),
                                ts == null ? null : ts.toInstant());
                    });
            if (rows.isEmpty()) {
                return null;
            }
            QueuedJob q = rows.get(0);
            jobService.markRunning(q.id());
            return q;
        });

        if (job == null) {
            return false;
        }

        try {
            JsonNode refs = readTree(job.snapshotRefsJson());
            long activityId = refs.path("activityId").asLong(0);
            String activityStatus = activityId > 0 ? loadActivityStatus(activityId) : null;
            if (!"PREPARING".equals(activityStatus)) {
                jobService.expire(job.id(), "ACTIVITY_CANCELLED");
                return true;
            }

            boolean timedOut = job.deadlineAt() != null && job.deadlineAt().isBefore(Instant.now());
            QuestionGenerationService.GenerationOutcome outcome;
            if (timedOut) {
                outcome = new QuestionGenerationService.GenerationOutcome(List.of(), null, null, "AI_TIMEOUT");
            } else {
                int gap = refs.path("gap").asInt(0);
                outcome = questionGenerator.generate(
                        job.id(),
                        job.userId(),
                        job.knowledgeId() == null ? 0L : job.knowledgeId(),
                        job.sourceQuestionVersionId(),
                        gap);
            }

            tx.executeWithoutResult(status -> finalizeJob(job, refs, outcome, timedOut));
            return true;
        } catch (RuntimeException ex) {
            log.error("Unexpected error in AiJobWorker for job={}", job.id(), ex);
            tx.executeWithoutResult(status -> {
                // Lock activity FIRST, then job (activity -> job lock order)
                jobService.markActivityPreparationFailed(job.snapshotRefsJson(), "AI_WORKER_ERROR");
                jobService.fail(job.id(), "AI_WORKER_ERROR");
            });
            return true;
        }
    }

    private void finalizeJob(
            QueuedJob job,
            JsonNode refs,
            QuestionGenerationService.GenerationOutcome outcome,
            boolean timedOut) {
        long activityId = refs.path("activityId").asLong(0);
        var actRows = db.query(
                "SELECT status FROM rs_learning_activity WHERE id=? FOR UPDATE",
                (r, n) -> r.getString(1),
                activityId);
        if (actRows.isEmpty() || !"PREPARING".equals(actRows.get(0))) {
            // Activity was cancelled while worker was running; discard late results.
            jobService.expire(job.id(), "ACTIVITY_CANCELLED");
            return;
        }

        String kind = refs.path("kind").asText("PRACTICE");
        String degradeReason = outcome.errorCode() != null ? outcome.errorCode() : "AI_REJECTED";
        if ("TASK".equals(kind)) {
            finalizeTask(refs, outcome.questions(), degradeReason);
        } else {
            finalizePractice(refs, outcome.questions(), degradeReason);
        }

        db.update(
                "UPDATE rs_learning_activity SET status='READY', updated_at=CURRENT_TIMESTAMP WHERE id=?",
                activityId);

        if (timedOut) {
            jobService.expire(job.id(), "AI_TIMEOUT");
        } else if (!outcome.questions().isEmpty()) {
            jobService.succeed(job.id(), outcome.outputJson(), outcome.usageJson());
        } else {
            jobService.fail(job.id(), degradeReason);
        }
    }

    private void finalizePractice(
            JsonNode refs,
            List<QuestionGenerationService.GeneratedQuestion> aiQuestions,
            String degradeReason) {
        long practiceId = refs.path("practiceId").asLong();
        int gap = refs.path("gap").asInt(0);
        int requestedCount = refs.path("requestedCount").asInt(0);
        List<Long> bankIds = new ArrayList<>();
        refs.path("bankQuestionIds").forEach(n -> bankIds.add(n.asLong()));
        List<Long> knowledgeIds = new ArrayList<>();
        refs.path("knowledgeIds").forEach(n -> knowledgeIds.add(n.asLong()));

        Set<Long> usedIds = new HashSet<>(bankIds);
        for (var gq : aiQuestions) {
            usedIds.add(gq.questionId());
        }

        int pos = bankIds.size();
        int aiCount = 0;
        for (var gq : aiQuestions) {
            if (aiCount >= gap) {
                break;
            }
            db.update(
                    "INSERT INTO rs_practice_item(practice_id, question_id, question_version_id, knowledge_id, question_type, max_score, position) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    practiceId, gq.questionId(), gq.versionId(), gq.knowledgeId(), gq.type(), gq.maxScore(), pos++);
            aiCount++;
        }

        int remainingGap = gap - aiCount;
        List<FallbackQuestion> fallbacks = remainingGap > 0
                ? findFallbackBankQuestions(knowledgeIds, usedIds, remainingGap)
                : List.of();
        for (var fb : fallbacks) {
            db.update(
                    "INSERT INTO rs_practice_item(practice_id, question_id, question_version_id, knowledge_id, question_type, max_score, position) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    practiceId, fb.questionId(), fb.versionId(), fb.knowledgeId(), fb.type(), fb.maxScore(), pos++);
        }

        int totalCount = pos;
        int actualBank = bankIds.size() + fallbacks.size();
        String mixStr = db.queryForObject(
                "SELECT mix_ratio_json::text FROM rs_practice WHERE id=?",
                String.class,
                practiceId);
        ObjectNode mixNode = (ObjectNode) readTree(mixStr);
        ObjectNode actual = mixNode.putObject("actual");
        actual.put("bank", actualBank);
        actual.put("ai", aiCount);
        ArrayNode degraded = mixNode.putArray("degraded");
        int degradedStart = bankIds.size() + aiCount;
        for (int i = 0; i < fallbacks.size(); i++) {
            ObjectNode entry = degraded.addObject();
            entry.put("position", degradedStart + i);
            entry.put("reason", degradeReason);
        }
        refreshShortage(mixNode, requestedCount > 0 ? requestedCount : totalCount, totalCount, bankIds.size());

        db.update(
                "UPDATE rs_practice SET question_count=?, mix_ratio_json=CAST(? AS jsonb) WHERE id=?",
                totalCount, writeJson(mixNode), practiceId);
    }

    private void finalizeTask(
            JsonNode refs,
            List<QuestionGenerationService.GeneratedQuestion> aiQuestions,
            String degradeReason) {
        long taskId = refs.path("taskId").asLong();
        int gap = refs.path("gap").asInt(0);
        int requestedCount = refs.path("requestedCount").asInt(0);
        int bankSize = refs.path("bankQuestionIds").size();

        int pos = bankSize;
        int aiCount = 0;
        for (var gq : aiQuestions) {
            if (aiCount >= gap) {
                break;
            }
            ObjectNode reason = json.createObjectNode();
            reason.put("pool", "AI_GENERATED");
            reason.put("selectionCategory", "AI_GENERATED");
            db.update(
                    "INSERT INTO rs_task_item(task_id, question_id, question_version_id, knowledge_id, question_type, max_score, position, reason_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
                    taskId, gq.questionId(), gq.versionId(), gq.knowledgeId(), gq.type(), gq.maxScore(), pos++, writeJson(reason));
            aiCount++;
        }

        int remainingGap = gap - aiCount;
        int fallbackAdded = 0;
        JsonNode fallbackNodes = refs.path("fallbackItems");
        if (remainingGap > 0 && fallbackNodes.isArray()) {
            for (int i = 0; i < fallbackNodes.size() && fallbackAdded < remainingGap; i++) {
                JsonNode fb = fallbackNodes.get(i);
                db.update(
                        "INSERT INTO rs_task_item(task_id, question_id, question_version_id, knowledge_id, question_type, max_score, position, reason_json) "
                                + "SELECT ?, q.id, v.id, v.knowledge_point_id, v.type, v.max_score, ?, CAST(? AS jsonb) "
                                + "FROM rs_question q JOIN rs_question_version v ON v.id=? WHERE q.id=?",
                        taskId,
                        pos++,
                        writeJson(fb.get("reason")),
                        fb.get("questionVersionId").asLong(),
                        fb.get("questionId").asLong());
                fallbackAdded++;
            }
        }

        int totalCount = pos;
        int actualBank = bankSize + fallbackAdded;
        String mixStr = db.queryForObject(
                "SELECT mix_ratio_json::text FROM rs_review_task WHERE id=?",
                String.class,
                taskId);
        ObjectNode mixNode = (ObjectNode) readTree(mixStr);
        ObjectNode actual = mixNode.putObject("actual");
        actual.put("bank", actualBank);
        actual.put("ai", aiCount);
        ArrayNode degraded = mixNode.putArray("degraded");
        int degradedStart = bankSize + aiCount;
        for (int i = 0; i < fallbackAdded; i++) {
            ObjectNode entry = degraded.addObject();
            entry.put("position", degradedStart + i);
            entry.put("reason", degradeReason);
        }
        refreshShortage(mixNode, requestedCount > 0 ? requestedCount : totalCount, totalCount, bankSize);

        db.update(
                "UPDATE rs_review_task SET status='READY', question_count=?, mix_ratio_json=CAST(? AS jsonb) WHERE id=?",
                totalCount, writeJson(mixNode), taskId);
    }

    private void refreshShortage(ObjectNode mixNode, int requestedCount, int totalCount, int initialBankCount) {
        int plannedBank = mixNode.path("planned").path("bank").asInt(0);
        int bankShortfall = Math.max(0, plannedBank - initialBankCount);
        if (bankShortfall > 0 || totalCount < requestedCount) {
            ObjectNode shortage = mixNode.putObject("shortage");
            shortage.put("requested", requestedCount);
            shortage.put("actual", totalCount);
            if (bankShortfall > 0) {
                shortage.put("bankShortfall", bankShortfall);
            }
            shortage.put("reason", bankShortfall > 0 ? "INSUFFICIENT_RECOMMENDATIONS" : "AI_UNAVAILABLE");
        } else {
            mixNode.remove("shortage");
        }
    }

    private List<FallbackQuestion> findFallbackBankQuestions(
            List<Long> knowledgeIds, Set<Long> excludeIds, int needed) {
        if (knowledgeIds.isEmpty() || needed <= 0) {
            return List.of();
        }
        List<FallbackQuestion> result = new ArrayList<>();
        for (long kp : knowledgeIds) {
            if (result.size() >= needed) {
                break;
            }
            var candidates = db.query(
                    "SELECT q.id, v.id, v.knowledge_point_id, v.type, v.max_score "
                            + "FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id "
                            + "WHERE q.subject_id=1 AND q.status='ACTIVE' AND v.knowledge_point_id=? "
                            + "ORDER BY q.id",
                    (r, n) -> new FallbackQuestion(r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4), r.getDouble(5)),
                    kp);
            for (var c : candidates) {
                if (result.size() >= needed) {
                    break;
                }
                if (excludeIds.add(c.questionId())) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    private String loadActivityStatus(long activityId) {
        var rows = db.query("SELECT status FROM rs_learning_activity WHERE id=?", (r, n) -> r.getString(1), activityId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private JsonNode readTree(String s) {
        try {
            return s == null ? json.createObjectNode() : json.readTree(s);
        } catch (JsonProcessingException e) {
            return json.createObjectNode();
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private record QueuedJob(
            long id,
            long userId,
            String snapshotRefsJson,
            Long sourceQuestionVersionId,
            Long knowledgeId,
            Instant deadlineAt) {}

    private record FallbackQuestion(
            long questionId,
            long versionId,
            long knowledgeId,
            String type,
            double maxScore) {}
}
