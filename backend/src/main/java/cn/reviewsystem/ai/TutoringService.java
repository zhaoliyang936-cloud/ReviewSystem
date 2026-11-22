package cn.reviewsystem.ai;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.material.MaterialSearchPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TutoringService {
    private static final Logger log = LoggerFactory.getLogger(TutoringService.class);
    private static final Set<String> VALID_SCENARIOS = Set.of("GUIDANCE", "EXPLANATION");
    private static final Set<String> VALID_FEEDBACK_CATEGORIES = Set.of("HELPFUL", "UNCLEAR", "SUSPECTED_ERROR");

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AiProperties properties;
    private final AiQuotaService quotaService;
    private final AiJobService jobService;
    private final LlmClient llmClient;
    private final PromptTemplates prompts;
    private final MaterialSearchPort materialSearch;
    private final CitationValidator citationValidator;
    private final TransactionTemplate tx;

    @Autowired
    public TutoringService(
            JdbcTemplate db,
            ObjectMapper json,
            AiProperties properties,
            AiQuotaService quotaService,
            AiJobService jobService,
            LlmClient llmClient,
            PromptTemplates prompts,
            MaterialSearchPort materialSearch,
            CitationValidator citationValidator,
            PlatformTransactionManager txManager) {
        this.db = db;
        this.json = json;
        this.properties = properties;
        this.quotaService = quotaService;
        this.jobService = jobService;
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.materialSearch = materialSearch;
        this.citationValidator = citationValidator;
        this.tx = new TransactionTemplate(txManager);
    }

    public record CreateJobRequest(
            String scenario,
            Long knowledgeId,
            Long wrongQuestionId,
            String query,
            String userId,
            String stem,
            String answer,
            String subjectId) {}

    public record RuleFacts(
            int n,
            BigDecimal ruleScore,
            BigDecimal finalScore,
            String status,
            OffsetDateTime latestPracticeAt) {}

    public record JobView(
            long jobId,
            String scenario,
            String state,
            String status,
            String errorCode,
            String message,
            RuleFacts ruleFacts,
            JsonNode output,
            List<CitationValidator.CitationDto> citations,
            boolean expiredCleaned,
            String templateVersion,
            OffsetDateTime createdAt) {}

    public record CreateResult(int httpStatus, JobView view) {}

    public record FeedbackRequest(String category, String comment, String userId) {}

    public record FeedbackView(
            String id,
            String jobId,
            String category,
            String comment,
            String status,
            OffsetDateTime createdAt) {}

    @Transactional
    public CreateResult createJob(long userId, CreateJobRequest req) {
        if (req == null) {
            validation("Request body is required");
        }
        if (req.userId() != null || req.stem() != null || req.answer() != null || req.subjectId() != null) {
            validation("userId, subjectId, stem and answer are server controlled");
        }
        String scenario = req.scenario() == null ? "" : req.scenario().trim();
        if (!VALID_SCENARIOS.contains(scenario)) {
            validation("scenario must be GUIDANCE or EXPLANATION");
        }

        if (hasActiveActivity(userId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "AI_BLOCKED_BY_TASK", "请先完成当前学习活动再使用 AI 辅导");
        }

        long knowledgeId;
        String knowledgeName;
        String searchQuery;
        Long wrongQuestionId = null;
        Long sourceQuestionVersionId = null;
        String questionContext = "";
        String studentWrongAnswer = "";

        if ("GUIDANCE".equals(scenario)) {
            if (req.knowledgeId() == null) {
                validation("knowledgeId is required for GUIDANCE");
            }
            knowledgeId = req.knowledgeId();
            var kRows = db.query(
                    "SELECT k.name FROM rs_knowledge_point k JOIN rs_chapter c ON c.id = k.chapter_id "
                            + "WHERE k.id = ? AND k.subject_id = 1 AND k.enabled AND c.enabled",
                    (r, n) -> r.getString(1),
                    knowledgeId);
            if (kRows.isEmpty()) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Knowledge point not found");
            }
            knowledgeName = kRows.get(0);
            searchQuery = req.query() == null ? "" : req.query().trim();
        } else {
            if (req.wrongQuestionId() == null) {
                validation("wrongQuestionId is required for EXPLANATION");
            }
            wrongQuestionId = req.wrongQuestionId();
            var wqRows = db.query(
                    """
                    SELECT wq.knowledge_id, k.name, wq.question_version_id,
                           v.type, v.content_json::text, v.answer_json::text, v.rubric_json::text,
                           la.attempt_type, la.practice_id, la.task_item_id, la.answer_json::text
                    FROM rs_wrong_question wq
                    JOIN rs_knowledge_point k ON k.id = wq.knowledge_id
                    JOIN rs_question_version v ON v.id = wq.question_version_id
                    LEFT JOIN rs_wrong_attempt la ON la.id = wq.latest_attempt_id
                    WHERE wq.id = ? AND wq.user_id = ?
                    """,
                    (r, n) -> new Object[]{
                            r.getLong(1), r.getString(2), r.getLong(3),
                            r.getString(4), r.getString(5), r.getString(6), r.getString(7),
                            r.getString(8), r.getObject(9, Long.class), r.getObject(10, Long.class), r.getString(11)
                    },
                    wrongQuestionId, userId);
            if (wqRows.isEmpty()) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Wrong question not found");
            }
            Object[] row = wqRows.get(0);
            knowledgeId = (Long) row[0];
            knowledgeName = (String) row[1];
            sourceQuestionVersionId = (Long) row[2];
            Long practiceId = (Long) row[8];
            Long taskItemId = (Long) row[9];
            if (!isAttemptActivityCompleted(practiceId, taskItemId)) {
                throw new BusinessException(HttpStatus.CONFLICT, "AI_BLOCKED_BY_TASK", "练习或任务完成后才能查看 AI 讲解");
            }
            String contentJson = (String) row[4];
            searchQuery = extractStem(contentJson);
            ObjectNode qCtx = json.createObjectNode();
            qCtx.put("type", (String) row[3]);
            qCtx.set("content", readTree(contentJson));
            qCtx.set("answer", readTree((String) row[5]));
            if (row[6] != null) {
                qCtx.set("rubric", readTree((String) row[6]));
            }
            questionContext = writeJson(qCtx);
            studentWrongAnswer = row[10] == null ? "{}" : (String) row[10];
        }

        RuleFacts ruleFacts = loadRuleFacts(userId, knowledgeId);
        String evidenceSummary = loadEvidenceSummary(userId, knowledgeId, ruleFacts);

        List<MaterialSearchPort.MaterialChunkHit> hits = materialSearch.search(knowledgeId, searchQuery);
        if (hits == null) {
            hits = List.of();
        }
        int tutoringEpoch = ensureAndReadTutoringEpoch(userId);

        ObjectNode snapshotRefs = json.createObjectNode();
        snapshotRefs.put("scenario", scenario);
        snapshotRefs.put("knowledgeId", knowledgeId);
        snapshotRefs.put("knowledgeName", knowledgeName);
        if (wrongQuestionId != null) {
            snapshotRefs.put("wrongQuestionId", wrongQuestionId);
        }
        snapshotRefs.put("query", searchQuery);
        snapshotRefs.put("evidenceSummary", evidenceSummary);
        snapshotRefs.set("ruleFacts", ruleFactsToJson(ruleFacts));
        if (!questionContext.isEmpty()) {
            snapshotRefs.put("questionContext", questionContext);
            snapshotRefs.put("studentWrongAnswer", studentWrongAnswer);
        }

        if (hits.isEmpty()) {
            String contextHash = buildContextHash(userId, scenario, wrongQuestionId != null ? wrongQuestionId : knowledgeId, tutoringEpoch);
            long jobId = jobService.createJob(
                    userId,
                    scenario,
                    contextHash,
                    null,
                    snapshotRefs,
                    json.createArrayNode(),
                    sourceQuestionVersionId,
                    knowledgeId,
                    null,
                    tutoringEpoch,
                    "FAILED",
                    null);
            jobService.fail(jobId, "INSUFFICIENT_SOURCE");
            JobView view = getJob(userId, jobId);
            return new CreateResult(HttpStatus.OK.value(), view);
        }

        if (hasMaterialConflict(hits)) {
            ArrayNode materialRefs = buildMaterialRefsArray(hits);
            String contextHash = buildContextHash(userId, scenario, wrongQuestionId != null ? wrongQuestionId : knowledgeId, tutoringEpoch);
            long jobId = jobService.createJob(
                    userId,
                    scenario,
                    contextHash,
                    null,
                    snapshotRefs,
                    materialRefs,
                    sourceQuestionVersionId,
                    knowledgeId,
                    null,
                    tutoringEpoch,
                    "FAILED",
                    null);
            jobService.fail(jobId, "AI_SOURCE_CONFLICT");
            JobView view = getJob(userId, jobId);
            return new CreateResult(HttpStatus.OK.value(), view);
        }

        if (!properties.isConfigured()) {
            throw new BusinessException(HttpStatus.CONFLICT, "AI_UNAVAILABLE", "AI_DISABLED");
        }
        if (!quotaService.hasRemainingQuota(userId, AiQuotaService.Bucket.TUTORING)) {
            throw new BusinessException(HttpStatus.CONFLICT, "AI_UNAVAILABLE", "AI_QUOTA_EXCEEDED");
        }

        ArrayNode materialRefs = buildMaterialRefsArray(hits);
        String activeKey = "GUIDANCE".equals(scenario)
                ? "guidance:" + userId + ":" + tutoringEpoch
                : "explanation:" + userId + ":" + wrongQuestionId + ":" + tutoringEpoch;
        String contextHash = buildContextHash(userId, scenario, wrongQuestionId != null ? wrongQuestionId : knowledgeId, tutoringEpoch);
        OffsetDateTime deadlineAt = OffsetDateTime.now().plusSeconds(Math.max(5L, properties.getTimeoutMs() / 1000L));

        long jobId = jobService.createJob(
                userId,
                scenario,
                contextHash,
                activeKey,
                snapshotRefs,
                materialRefs,
                sourceQuestionVersionId,
                knowledgeId,
                null,
                tutoringEpoch,
                "QUEUED",
                deadlineAt);

        JobView view = getJob(userId, jobId);
        return new CreateResult(HttpStatus.ACCEPTED.value(), view);
    }

    public JobView getJob(long userId, long jobId) {
        var rows = db.query(
                """
                SELECT id, scenario, state, error_code, snapshot_refs_json::text,
                       material_refs_json::text, output_json::text, template_version, created_at
                FROM rs_ai_job
                WHERE id = ? AND user_id = ?
                """,
                (r, n) -> new Object[]{
                        r.getLong(1),
                        r.getString(2),
                        r.getString(3),
                        r.getString(4),
                        r.getString(5),
                        r.getString(6),
                        r.getString(7),
                        r.getString(8),
                        r.getObject(9, OffsetDateTime.class)
                },
                jobId, userId);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "AI job not found");
        }
        Object[] row = rows.get(0);
        String scenario = (String) row[1];
        String state = (String) row[2];
        String errorCode = (String) row[3];
        JsonNode snapshotRefs = readNullableTree((String) row[4]);
        JsonNode materialRefs = readNullableTree((String) row[5]);
        JsonNode outputJson = readNullableTree((String) row[6]);
        String templateVersion = (String) row[7];
        OffsetDateTime createdAt = (OffsetDateTime) row[8];

        RuleFacts ruleFacts = parseRuleFacts(snapshotRefs == null ? null : snapshotRefs.get("ruleFacts"));
        boolean expiredCleaned = "SUCCEEDED".equals(state) && outputJson == null;

        String status = state;
        String message = null;
        if ("INSUFFICIENT_SOURCE".equals(errorCode)) {
            status = "INSUFFICIENT_SOURCE";
            message = "资料不足，已提供规则摘要";
        } else if ("AI_SOURCE_CONFLICT".equals(errorCode)) {
            status = "INSUFFICIENT_SOURCE";
            message = "课程资料存在冲突，已提供规则摘要";
        } else if (expiredCleaned) {
            message = "该结果已过期清理，可重新请求";
        }

        List<CitationValidator.CitationDto> citations = resolveCitations(materialRefs, outputJson);
        return new JobView(
                (Long) row[0],
                scenario,
                state,
                status,
                errorCode,
                message,
                ruleFacts,
                outputJson,
                citations,
                expiredCleaned,
                templateVersion,
                createdAt);
    }

    @Transactional
    public FeedbackView submitFeedback(long userId, long jobId, FeedbackRequest req) {
        if (req == null) {
            validation("Request body is required");
        }
        String category = req.category() == null ? "" : req.category().trim();
        if (!VALID_FEEDBACK_CATEGORIES.contains(category)) {
            validation("Invalid feedback category");
        }
        String comment = req.comment() == null ? "" : req.comment().trim();
        if (comment.length() > 500) {
            validation("comment must not exceed 500 characters");
        }

        Long count = db.queryForObject("SELECT count(*) FROM rs_ai_job WHERE id = ? AND user_id = ?", Long.class, jobId, userId);
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "AI job not found");
        }

        var rows = db.query(
                """
                INSERT INTO rs_ai_feedback(user_id, job_id, category, comment)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (job_id, user_id) DO UPDATE
                SET category = EXCLUDED.category, comment = EXCLUDED.comment
                RETURNING id, job_id, category, comment, status, created_at
                """,
                (r, n) -> new FeedbackView(
                        String.valueOf(r.getLong("id")),
                        String.valueOf(r.getLong("job_id")),
                        r.getString("category"),
                        r.getString("comment"),
                        r.getString("status"),
                        r.getObject("created_at", OffsetDateTime.class)),
                userId, jobId, category, comment);
        return rows.get(0);
    }

    public void processQueuedJob(long jobId) {
        var rows = db.query(
                """
                SELECT id, user_id, scenario, knowledge_id, tutoring_epoch,
                       snapshot_refs_json::text, material_refs_json::text, deadline_at
                FROM rs_ai_job WHERE id = ?
                """,
                (r, n) -> {
                    var ts = r.getTimestamp("deadline_at");
                    return new QueuedTutoringJob(
                            r.getLong("id"),
                            r.getLong("user_id"),
                            r.getString("scenario"),
                            r.getObject("knowledge_id", Long.class),
                            r.getObject("tutoring_epoch", Integer.class),
                            r.getString(6),
                            r.getString(7),
                            ts == null ? null : ts.toInstant());
                },
                jobId);
        if (rows.isEmpty()) {
            return;
        }
        QueuedTutoringJob job = rows.get(0);

        if (job.deadlineAt() != null && job.deadlineAt().isBefore(Instant.now())) {
            jobService.expire(jobId, "AI_TIMEOUT");
            return;
        }
        if (isTutoringBlocked(job.userId(), job.tutoringEpoch())) {
            jobService.expire(jobId, "TUTORING_BLOCKED");
            return;
        }
        if (!properties.isConfigured()) {
            jobService.fail(jobId, "AI_DISABLED");
            return;
        }
        if (!quotaService.tryConsume(job.userId(), AiQuotaService.Bucket.TUTORING)) {
            jobService.fail(jobId, "AI_QUOTA_EXCEEDED");
            return;
        }

        JsonNode refs = readNullableTree(job.snapshotRefsJson());
        JsonNode matRefs = readNullableTree(job.materialRefsJson());
        Map<String, MaterialSearchPort.MaterialChunkHit> candidateMap = buildCandidateMap(matRefs);
        if (candidateMap.isEmpty()) {
            jobService.fail(jobId, "INSUFFICIENT_SOURCE");
            return;
        }

        String materialsBlock = formatMaterialsForPrompt(candidateMap);
        String knowledgeName = refs == null ? "" : refs.path("knowledgeName").asText("");
        JsonNode ruleNode = refs == null ? null : refs.get("ruleFacts");
        int n = ruleNode == null ? 0 : ruleNode.path("n").asInt(0);
        String ruleScoreStr = ruleNode == null || ruleNode.path("ruleScore").isNull() ? "暂无" : ruleNode.path("ruleScore").asText("暂无");
        String statusStr = ruleNode == null ? "UNASSESSED" : ruleNode.path("status").asText("UNASSESSED");
        String evidenceSummary = refs == null ? "" : refs.path("evidenceSummary").asText("");
        long kpId = job.knowledgeId() == null ? 0L : job.knowledgeId();

        String allowedCitationIds = String.join(", ", candidateMap.keySet());
        String prompt;
        if ("GUIDANCE".equals(job.scenario())) {
            String query = refs == null ? "" : refs.path("query").asText("");
            String ruleFactsText = "知识点=" + knowledgeName
                    + ", n=" + n
                    + ", M_rule=" + ruleScoreStr
                    + ", status=" + statusStr
                    + ", 证据摘要=" + evidenceSummary;
            String modeInstruction = n == 0
                    ? "当前用户在该知识点尚无做题证据（冷启动，n=0），请输出「建议先做的诊断范围」，不得编造掌握度数值。"
                    : "当前用户已有 " + n + " 条做题证据，请结合规则事实与资料给出针对性复习步骤。";
            prompt = prompts.reviewGuidance(
                    ruleFactsText,
                    query,
                    materialsBlock,
                    allowedCitationIds,
                    modeInstruction);
        } else {
            String questionCtx = refs == null ? "{}" : refs.path("questionContext").asText("{}");
            String wrongAns = refs == null ? "{}" : refs.path("studentWrongAnswer").asText("{}");
            prompt = prompts.errorExplanation(
                    questionCtx,
                    wrongAns,
                    materialsBlock,
                    allowedCitationIds);
        }

        LlmResponse response;
        try {
            response = llmClient.complete(
                    new LlmRequest(prompt, true),
                    Duration.ofMillis(Math.max(1000L, properties.getTimeoutMs())));
        } catch (AiUnavailableException ex) {
            String errorCode = QuestionGenerationService.mapErrorCode(ex.getFailure());
            log.warn("Tutoring job={} failed: {}", jobId, errorCode);
            jobService.fail(jobId, errorCode);
            return;
        }

        CitationValidator.ValidatedTutoringOutput validated = citationValidator.validateAndSanitize(
                response.content(),
                job.scenario(),
                kpId,
                candidateMap);
        if (validated == null) {
            jobService.fail(jobId, "AI_OUTPUT_INVALID");
            return;
        }

        tx.executeWithoutResult(txStatus -> {
            if (isTutoringBlocked(job.userId(), job.tutoringEpoch())) {
                jobService.expire(jobId, "TUTORING_BLOCKED");
                return;
            }
            jobService.succeed(jobId, validated.outputJson(), response.usage() == null ? null : response.usage().toJson(json));
        });
    }

    private boolean isTutoringBlocked(long userId, Integer jobEpoch) {
        if (hasActiveActivity(userId)) {
            return true;
        }
        if (jobEpoch == null) {
            return false;
        }
        int currentEpoch = readTutoringEpoch(userId);
        return currentEpoch != jobEpoch;
    }

    private boolean hasActiveActivity(long userId) {
        Long cnt = db.queryForObject(
                "SELECT count(*) FROM rs_learning_activity "
                        + "WHERE user_id = ? AND subject_id = 1 AND status IN ('PREPARING','READY','IN_PROGRESS','GRADING')",
                Long.class,
                userId);
        return cnt != null && cnt > 0;
    }

    private boolean isAttemptActivityCompleted(Long practiceId, Long taskItemId) {
        if (practiceId != null) {
            var statuses = db.query(
                    "SELECT a.status FROM rs_practice p JOIN rs_learning_activity a ON a.id = p.activity_id WHERE p.id = ?",
                    (r, n) -> r.getString(1),
                    practiceId);
            return !statuses.isEmpty() && "COMPLETED".equals(statuses.get(0));
        }
        if (taskItemId != null) {
            var statuses = db.query(
                    "SELECT a.status FROM rs_task_item ti JOIN rs_review_task t ON t.id = ti.task_id JOIN rs_learning_activity a ON a.id = t.activity_id WHERE ti.id = ?",
                    (r, n) -> r.getString(1),
                    taskItemId);
            return !statuses.isEmpty() && "COMPLETED".equals(statuses.get(0));
        }
        return true;
    }

    private int ensureAndReadTutoringEpoch(long userId) {
        db.update("INSERT INTO rs_student_course_guard(user_id, subject_id) VALUES (?, 1) ON CONFLICT DO NOTHING", userId);
        return readTutoringEpoch(userId);
    }

    private int readTutoringEpoch(long userId) {
        var rows = db.query(
                "SELECT tutoring_epoch FROM rs_student_course_guard WHERE user_id = ? AND subject_id = 1",
                (r, n) -> r.getInt(1),
                userId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private RuleFacts loadRuleFacts(long userId, long knowledgeId) {
        var rows = db.query(
                """
                SELECT s.n, s.rule_score, s.final_score, s.status, s.latest_practice_at
                FROM rs_mastery_current mc
                JOIN rs_mastery_snapshot s ON s.id = mc.snapshot_id
                WHERE mc.user_id = ? AND mc.knowledge_id = ?
                """,
                (r, n) -> new RuleFacts(
                        r.getInt(1),
                        r.getBigDecimal(2),
                        r.getBigDecimal(3),
                        r.getString(4),
                        r.getObject(5, OffsetDateTime.class)),
                userId, knowledgeId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return new RuleFacts(0, null, null, "UNASSESSED", null);
    }

    private String loadEvidenceSummary(long userId, long knowledgeId, RuleFacts facts) {
        if (facts.n() == 0) {
            return "该用户在此知识点暂无做题证据（n=0，冷启动）。";
        }
        var items = db.query(
                """
                SELECT e.question_type, e.result_y, e.submitted_at
                FROM rs_evidence e
                WHERE e.user_id = ? AND e.knowledge_id = ? AND NOT e.revoked
                ORDER BY e.submitted_at DESC, e.id DESC
                LIMIT 10
                """,
                (r, n) -> r.getString(1) + "(y=" + r.getBigDecimal(2) + ")",
                userId, knowledgeId);
        return "最近证据(" + facts.n() + "题, M_rule=" + facts.ruleScore() + "): " + String.join(", ", items);
    }

    static boolean hasMaterialConflict(List<MaterialSearchPort.MaterialChunkHit> hits) {
        if (hits == null || hits.size() < 2) {
            return false;
        }
        for (int i = 0; i < hits.size(); i++) {
            for (int j = i + 1; j < hits.size(); j++) {
                MaterialSearchPort.MaterialChunkHit a = hits.get(i);
                MaterialSearchPort.MaterialChunkHit b = hits.get(j);
                if (a.materialVersionId() == b.materialVersionId()) {
                    continue;
                }
                String ta = a.text() == null ? "" : a.text();
                String tb = b.text() == null ? "" : b.text();
                if (isContradictoryPair(ta, tb)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isContradictoryPair(String a, String b) {
        String[][] oppositions = {
                {"先进先出", "先进后出"},
                {"FIFO", "LIFO"},
                {"O(1)", "O(n)"},
                {"O(log n)", "O(n^2)"},
                {"必须", "禁止"},
                {"支持", "不支持"},
                {"允许", "不允许"},
                {"正确", "错误"},
                {"有序", "无序"},
                {"稳定", "不稳定"}
        };
        for (String[] pair : oppositions) {
            if ((a.contains(pair[0]) && b.contains(pair[1])) || (a.contains(pair[1]) && b.contains(pair[0]))) {
                return true;
            }
        }
        return false;
    }

    private ArrayNode buildMaterialRefsArray(List<MaterialSearchPort.MaterialChunkHit> hits) {
        ArrayNode arr = json.createArrayNode();
        int idx = 1;
        for (MaterialSearchPort.MaterialChunkHit hit : hits) {
            ObjectNode n = arr.addObject();
            n.put("id", "c" + idx++);
            n.put("materialVersionId", hit.materialVersionId());
            n.put("chunkId", hit.chunkId());
            n.put("title", hit.title() == null ? "" : hit.title());
            n.put("sourceLabel", hit.sourceLabel() == null ? "" : hit.sourceLabel());
            n.put("text", hit.text() == null ? "" : hit.text());
        }
        return arr;
    }

    private Map<String, MaterialSearchPort.MaterialChunkHit> buildCandidateMap(JsonNode matRefs) {
        Map<String, MaterialSearchPort.MaterialChunkHit> map = new LinkedHashMap<>();
        if (matRefs != null && matRefs.isArray()) {
            for (JsonNode n : matRefs) {
                String id = n.path("id").asText("");
                if (!id.isBlank()) {
                    map.put(id, new MaterialSearchPort.MaterialChunkHit(
                            n.path("materialVersionId").asLong(),
                            n.path("chunkId").asLong(),
                            n.path("title").asText(""),
                            n.path("sourceLabel").asText(""),
                            n.path("text").asText(""),
                            1));
                }
            }
        }
        return map;
    }

    private String formatMaterialsForPrompt(Map<String, MaterialSearchPort.MaterialChunkHit> candidates) {
        StringBuilder sb = new StringBuilder();
        for (var entry : candidates.entrySet()) {
            var h = entry.getValue();
            sb.append("[").append(entry.getKey()).append("] ")
                    .append(h.title()).append("：")
                    .append(h.text()).append("\n");
        }
        return sb.toString();
    }

    private List<CitationValidator.CitationDto> resolveCitations(JsonNode materialRefs, JsonNode outputJson) {
        if (materialRefs == null || !materialRefs.isArray()) {
            return List.of();
        }
        Set<String> usedIds = null;
        if (outputJson != null && outputJson.has("citationIds") && outputJson.get("citationIds").isArray()) {
            usedIds = new LinkedHashSet<>();
            for (JsonNode c : outputJson.get("citationIds")) {
                if (c.isTextual()) {
                    usedIds.add(c.asText());
                }
            }
        }
        List<CitationValidator.CitationDto> result = new ArrayList<>();
        for (JsonNode n : materialRefs) {
            String cid = n.path("id").asText("");
            if (usedIds == null || usedIds.contains(cid)) {
                result.add(new CitationValidator.CitationDto(
                        cid,
                        String.valueOf(n.path("materialVersionId").asLong()),
                        n.path("title").asText(""),
                        String.valueOf(n.path("chunkId").asLong())));
            }
        }
        return result;
    }

    private ObjectNode ruleFactsToJson(RuleFacts f) {
        ObjectNode node = json.createObjectNode();
        node.put("n", f.n());
        if (f.ruleScore() == null) node.putNull("ruleScore");
        else node.put("ruleScore", f.ruleScore());
        if (f.finalScore() == null) node.putNull("finalScore");
        else node.put("finalScore", f.finalScore());
        node.put("status", f.status());
        if (f.latestPracticeAt() == null) node.putNull("latestPracticeAt");
        else node.put("latestPracticeAt", f.latestPracticeAt().toString());
        return node;
    }

    private RuleFacts parseRuleFacts(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        BigDecimal rule = node.path("ruleScore").isNumber() ? node.get("ruleScore").decimalValue() : null;
        BigDecimal fin = node.path("finalScore").isNumber() ? node.get("finalScore").decimalValue() : null;
        OffsetDateTime latest = node.path("latestPracticeAt").isTextual()
                ? OffsetDateTime.parse(node.get("latestPracticeAt").asText())
                : null;
        return new RuleFacts(
                node.path("n").asInt(0),
                rule,
                fin,
                node.path("status").asText("UNASSESSED"),
                latest);
    }

    private String buildContextHash(long userId, String scenario, long bizId, int epoch) {
        String tplVer = PromptTemplates.versionFor(scenario);
        String model = properties.getModel() == null || properties.getModel().isBlank()
                ? "unconfigured"
                : properties.getModel();
        return AiJobService.sha256(userId + ":" + scenario + ":" + bizId + ":" + epoch + ":" + tplVer + ":" + model);
    }

    private String extractStem(String contentJson) {
        try {
            return json.readTree(contentJson).path("stem").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private JsonNode readTree(String s) {
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readNullableTree(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void validation(String message) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private record QueuedTutoringJob(
            long id,
            long userId,
            String scenario,
            Long knowledgeId,
            Integer tutoringEpoch,
            String snapshotRefsJson,
            String materialRefsJson,
            Instant deadlineAt) {}
}
