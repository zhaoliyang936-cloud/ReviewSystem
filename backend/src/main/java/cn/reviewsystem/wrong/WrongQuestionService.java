package cn.reviewsystem.wrong;

import cn.reviewsystem.ai.AiJobService;
import cn.reviewsystem.ai.AiProperties;
import cn.reviewsystem.ai.PromptTemplates;
import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.practice.AiAvailabilityPort;
import cn.reviewsystem.practice.PracticeDtos;
import cn.reviewsystem.practice.PracticeService;
import cn.reviewsystem.question.PageResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WrongQuestionService {

    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "MASTERED", "ALL");
    private static final Set<String> VALID_TYPES = Set.of("CHOICE", "FILL", "SHORT_ANSWER", "ALGORITHM");
    private static final Set<String> VALID_SOURCES = Set.of("MANUAL", "AI_GENERATED");
    private static final Set<String> VALID_CATEGORIES = Set.of("SUSPECTED_ERROR", "CONTENT_PROBLEM", "OTHER");

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final PracticeService practices;
    private final AiAvailabilityPort ai;
    private final AiJobService aiJobs;
    private final AiProperties aiProperties;

    public WrongQuestionService(JdbcTemplate db, ObjectMapper json, PracticeService practices) {
        this(db, json, practices, null, null, null);
    }

    @Autowired
    public WrongQuestionService(
            JdbcTemplate db,
            ObjectMapper json,
            PracticeService practices,
            @Autowired(required = false) AiAvailabilityPort ai,
            @Autowired(required = false) AiJobService aiJobs,
            @Autowired(required = false) AiProperties aiProperties
    ) {
        this.db = db;
        this.json = json;
        this.practices = practices;
        this.ai = ai;
        this.aiJobs = aiJobs;
        this.aiProperties = aiProperties;
    }

    public PageResult<WrongQuestionDtos.ListItem> list(
            long userId,
            String status,
            String knowledgeId,
            String type,
            String source,
            int page,
            int size
    ) {
        if (page < 0) {
            validation("page must not be negative");
        }
        if (size < 1) {
            validation("size must be positive");
        }
        int actualSize = Math.min(size, 100);

        String effectiveStatus = (status == null || status.isBlank()) ? "PENDING" : status.trim();
        if (!VALID_STATUSES.contains(effectiveStatus)) {
            validation("Invalid status filter");
        }

        StringBuilder where = new StringBuilder(" WHERE wq.user_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (!"ALL".equals(effectiveStatus)) {
            where.append(" AND wq.status = ?");
            params.add(effectiveStatus);
        }
        if (knowledgeId != null && !knowledgeId.isBlank()) {
            where.append(" AND wq.knowledge_id = ?");
            params.add(parseId(knowledgeId.trim()));
        }
        if (type != null && !type.isBlank()) {
            String trimmedType = type.trim();
            if (!VALID_TYPES.contains(trimmedType)) {
                validation("Invalid question type filter");
            }
            where.append(" AND v.type = ?");
            params.add(trimmedType);
        }
        if (source != null && !source.isBlank()) {
            String trimmedSource = source.trim();
            if (!VALID_SOURCES.contains(trimmedSource)) {
                validation("Invalid source filter");
            }
            where.append(" AND wq.source_type = ?");
            params.add(trimmedSource);
        }

        String fromSql = """
                FROM rs_wrong_question wq
                JOIN rs_question q ON q.id = wq.question_id
                JOIN rs_question_version v ON v.id = wq.question_version_id
                JOIN rs_knowledge_point k ON k.id = wq.knowledge_id
                LEFT JOIN rs_wrong_attempt la ON la.id = wq.latest_attempt_id
                """;

        Long total = db.queryForObject(
                "SELECT count(*) " + fromSql + where,
                Long.class,
                params.toArray()
        );

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(actualSize);
        queryParams.add(page * actualSize);

        String selectSql = """
                SELECT wq.id, wq.question_id, wq.question_version_id, wq.knowledge_id,
                       k.code, k.name, v.type, wq.source_type, q.status, wq.status,
                       wq.wrong_count, wq.first_wrong_at, wq.last_wrong_at, wq.mastered_at,
                       v.content_json->>'stem' AS stem, wq.origin_wrong_question_id,
                       la.id AS la_id, la.attempt_type AS la_type, la.practice_id AS la_practice_id,
                       la.item_id AS la_item_id, la.task_item_id AS la_task_item_id,
                       la.answer_json::text AS la_answer, la.grade_json::text AS la_grade,
                       la.passed AS la_passed, la.created_at AS la_created_at
                """ + fromSql + where +
                " ORDER BY CASE WHEN wq.status = 'PENDING' THEN 0 ELSE 1 END, wq.last_wrong_at DESC, wq.id DESC LIMIT ? OFFSET ?";

        var items = db.query(selectSql, (r, n) -> {
            WrongQuestionDtos.AttemptView latestAttempt = mapAttemptOrNull(r, "la_");
            boolean aiJudged = latestAttempt != null && latestAttempt.aiJudged();
            Long originWqId = r.getObject("origin_wrong_question_id", Long.class);
            return new WrongQuestionDtos.ListItem(
                    String.valueOf(r.getLong("id")),
                    String.valueOf(r.getLong("question_id")),
                    String.valueOf(r.getLong("question_version_id")),
                    String.valueOf(r.getLong("knowledge_id")),
                    r.getString("code"),
                    r.getString("name"),
                    r.getString("type"),
                    r.getString("source_type"),
                    r.getString(9),
                    r.getString(10),
                    r.getInt("wrong_count"),
                    r.getObject("first_wrong_at", OffsetDateTime.class),
                    r.getObject("last_wrong_at", OffsetDateTime.class),
                    r.getObject("mastered_at", OffsetDateTime.class),
                    r.getString("stem"),
                    aiJudged,
                    latestAttempt,
                    originWqId == null ? null : String.valueOf(originWqId)
            );
        }, queryParams.toArray());

        return new PageResult<>(items, page, actualSize, total == null ? 0 : total);
    }

    public WrongQuestionDtos.DetailView detail(long userId, long id) {
        var rows = db.query(
                """
                SELECT wq.id, wq.question_id, wq.question_version_id, wq.knowledge_id,
                       k.code, k.name, v.type, wq.source_type, q.status AS q_status, wq.status AS wq_status,
                       wq.wrong_count, wq.first_wrong_at, wq.last_wrong_at, wq.mastered_at,
                       wq.latest_attempt_id,
                       v.content_json::text, v.answer_json::text, v.rubric_json::text,
                       wq.origin_wrong_question_id
                FROM rs_wrong_question wq
                JOIN rs_question q ON q.id = wq.question_id
                JOIN rs_question_version v ON v.id = wq.question_version_id
                JOIN rs_knowledge_point k ON k.id = wq.knowledge_id
                WHERE wq.id = ? AND wq.user_id = ?
                """,
                (r, n) -> new DetailRow(
                        r.getLong("id"),
                        r.getLong("question_id"),
                        r.getLong("question_version_id"),
                        r.getLong("knowledge_id"),
                        r.getString("code"),
                        r.getString("name"),
                        r.getString("type"),
                        r.getString("source_type"),
                        r.getString("q_status"),
                        r.getString("wq_status"),
                        r.getInt("wrong_count"),
                        r.getObject("first_wrong_at", OffsetDateTime.class),
                        r.getObject("last_wrong_at", OffsetDateTime.class),
                        r.getObject("mastered_at", OffsetDateTime.class),
                        r.getObject("latest_attempt_id", Long.class),
                        r.getString(16),
                        r.getString(17),
                        r.getString(18),
                        r.getObject("origin_wrong_question_id", Long.class)
                ),
                id, userId
        );
        if (rows.isEmpty()) {
            notFound();
        }
        DetailRow row = rows.getFirst();

        List<WrongQuestionDtos.AttemptView> attempts = db.query(
                """
                SELECT id, attempt_type, practice_id, item_id, task_item_id,
                       answer_json::text AS answer, grade_json::text AS grade,
                       passed, created_at
                FROM rs_wrong_attempt
                WHERE wrong_question_id = ?
                ORDER BY created_at DESC, id DESC
                """,
                (r, n) -> mapAttempt(r, ""),
                row.id()
        );

        WrongQuestionDtos.AttemptView latestAttempt = null;
        if (row.latestAttemptId() != null) {
            String latestIdStr = String.valueOf(row.latestAttemptId());
            latestAttempt = attempts.stream()
                    .filter(a -> latestIdStr.equals(a.id()))
                    .findFirst()
                    .orElse(null);
        }
        if (latestAttempt == null && !attempts.isEmpty()) {
            latestAttempt = attempts.getFirst();
        }

        boolean aiJudged = latestAttempt != null && latestAttempt.aiJudged();
        JsonNode storedContent = read(row.contentJson());
        String explanation = storedContent.path("explanation").isTextual()
                ? storedContent.path("explanation").asText()
                : "";

        WrongQuestionDtos.FeedbackView feedback = findFeedback(row.id(), userId);

        return new WrongQuestionDtos.DetailView(
                String.valueOf(row.id()),
                String.valueOf(row.questionId()),
                String.valueOf(row.questionVersionId()),
                String.valueOf(row.knowledgeId()),
                row.knowledgeCode(),
                row.knowledgeName(),
                row.questionType(),
                row.sourceType(),
                row.questionStatus(),
                row.status(),
                row.wrongCount(),
                row.firstWrongAt(),
                row.lastWrongAt(),
                row.masteredAt(),
                aiJudged,
                publicContent(storedContent),
                read(row.answerJson()),
                readNullable(row.rubricJson()),
                explanation,
                latestAttempt,
                attempts,
                feedback,
                row.originWrongQuestionId() == null ? null : String.valueOf(row.originWrongQuestionId())
        );
    }

    @Transactional
    public WrongQuestionDtos.VariantJobView createVariant(
            long userId,
            long wrongQuestionId,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 120) {
            validation("Idempotency-Key is required and must not exceed 120 characters");
        }
        String key = idempotencyKey.trim();
        db.query("SELECT pg_advisory_xact_lock(?)", r -> {}, userId);

        var wqRows = db.query(
                """
                SELECT wq.question_id, q.status, q.current_version_id, v.knowledge_point_id
                FROM rs_wrong_question wq
                JOIN rs_question q ON q.id = wq.question_id
                JOIN rs_question_version v ON v.id = q.current_version_id
                WHERE wq.id = ? AND wq.user_id = ?
                """,
                (r, n) -> new Object[]{
                        r.getLong(1),
                        r.getString(2),
                        r.getLong(3),
                        r.getLong(4)
                },
                wrongQuestionId, userId
        );
        if (wqRows.isEmpty()) {
            notFound();
        }
        Object[] wq = wqRows.getFirst();
        long questionId = (Long) wq[0];
        String questionStatus = (String) wq[1];
        long currentVersionId = (Long) wq[2];
        long knowledgeId = (Long) wq[3];

        var priorJobs = db.query(
                """
                SELECT id, state, (snapshot_refs_json->>'wrongQuestionId')::bigint
                FROM rs_ai_job
                WHERE user_id = ? AND scenario = 'WRONG_VARIANT' AND snapshot_refs_json->>'idempotencyKey' = ?
                ORDER BY id DESC LIMIT 1
                """,
                (r, n) -> new Object[]{
                        r.getLong(1),
                        r.getString(2),
                        r.getObject(3, Long.class)
                },
                userId, key
        );
        if (!priorJobs.isEmpty()) {
            Object[] prior = priorJobs.getFirst();
            Long priorWqId = (Long) prior[2];
            if (priorWqId != null && priorWqId == wrongQuestionId) {
                return new WrongQuestionDtos.VariantJobView((Long) prior[0], (String) prior[1]);
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used with a different request"
            );
        }

        if (!"ACTIVE".equals(questionStatus)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUESTION_SUSPENDED",
                    "Question is suspended and cannot generate variant"
            );
        }

        Long activeCount = db.queryForObject(
                "SELECT count(*) FROM rs_learning_activity WHERE user_id = ? AND subject_id = 1 AND status IN ('PREPARING','READY','IN_PROGRESS','GRADING')",
                Long.class,
                userId
        );
        if (activeCount != null && activeCount > 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "STATE_CONFLICT",
                    "An active learning activity already exists"
            );
        }

        AiAvailabilityPort.AiStatus aiStatus = ai != null
                ? ai.status(userId)
                : AiAvailabilityPort.AiStatus.no("AI_DISABLED");
        if (!aiStatus.available() || aiJobs == null) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "AI_UNAVAILABLE",
                    aiStatus.reasonCode() == null ? "AI_DISABLED" : aiStatus.reasonCode()
            );
        }

        ObjectNode refs = json.createObjectNode();
        refs.put("wrongQuestionId", wrongQuestionId);
        refs.put("questionId", questionId);
        refs.put("questionVersionId", currentVersionId);
        refs.put("knowledgeId", knowledgeId);
        refs.put("idempotencyKey", key);

        String activeKey = "variant:" + userId + ":" + wrongQuestionId;
        String templateVersion = PromptTemplates.versionFor("WRONG_VARIANT");
        String model = aiProperties != null && aiProperties.getModel() != null && !aiProperties.getModel().isBlank()
                ? aiProperties.getModel()
                : "unconfigured";
        String contextHash = AiJobService.sha256(
                userId + ":WRONG_VARIANT:" + wrongQuestionId + ":" + templateVersion + ":" + model);
        int timeoutSec = aiProperties != null ? Math.max(5, aiProperties.getPrepareTimeoutSeconds()) : 90;

        long jobId = aiJobs.createJob(
                userId,
                "WRONG_VARIANT",
                contextHash,
                activeKey,
                refs,
                currentVersionId,
                knowledgeId,
                null,
                "QUEUED",
                OffsetDateTime.now().plusSeconds(timeoutSec)
        );
        String jobState = db.queryForObject("SELECT state FROM rs_ai_job WHERE id = ?", String.class, jobId);
        return new WrongQuestionDtos.VariantJobView(jobId, jobState == null ? "QUEUED" : jobState);
    }

    @Transactional
    public PracticeDtos.PracticeView redo(
            long userId,
            long id,
            String idempotencyKey,
            WrongQuestionDtos.RedoRequest request
    ) {
        if (request != null) {
            if (request.userId() != null || request.subjectId() != null) {
                validation("userId and subjectId are server controlled");
            }
            if (request.mode() != null && !request.mode().isBlank() && !"ORIGINAL".equals(request.mode())) {
                validation("Unsupported redo mode");
            }
        }
        return practices.createForQuestion(userId, id, idempotencyKey);
    }

    @Transactional
    public WrongQuestionDtos.FeedbackView feedback(
            long userId,
            long id,
            WrongQuestionDtos.FeedbackRequest request
    ) {
        if (request == null) {
            validation("Request body is required");
        }
        if (request.userId() != null) {
            validation("userId is server controlled");
        }
        String category = request.category() == null ? "" : request.category().trim();
        if (!VALID_CATEGORIES.contains(category)) {
            validation("Invalid feedback category");
        }
        String comment = request.comment() == null ? "" : request.comment().trim();
        if (comment.isEmpty() || comment.length() > 500) {
            validation("comment is required and must not exceed 500 characters");
        }

        var wqRows = db.query(
                "SELECT question_id FROM rs_wrong_question WHERE id = ? AND user_id = ?",
                (r, n) -> r.getLong(1),
                id, userId
        );
        if (wqRows.isEmpty()) {
            notFound();
        }
        long questionId = wqRows.getFirst();
        db.query("SELECT pg_advisory_xact_lock(?)", r -> {}, 900_000_000L + questionId);

        WrongQuestionDtos.FeedbackView existing = findFeedback(id, userId);
        if (existing != null) {
            return existing;
        }

        var inserted = db.query(
                """
                INSERT INTO rs_question_feedback(user_id, wrong_question_id, question_id, category, comment)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (wrong_question_id, user_id) DO NOTHING
                RETURNING id, wrong_question_id, question_id, category, comment, status, created_at
                """,
                (r, n) -> mapFeedback(r),
                userId, id, questionId, category, comment
        );
        if ("SUSPECTED_ERROR".equals(category)) {
            Long distinctUsers = db.queryForObject(
                    """
                    SELECT count(DISTINCT user_id) FROM rs_question_feedback
                    WHERE question_id = ? AND category = 'SUSPECTED_ERROR' AND status <> 'CLOSED'
                    """,
                    Long.class,
                    questionId
            );
            if (distinctUsers != null && distinctUsers >= 3) {
                db.update(
                        """
                        INSERT INTO rs_question_review_case(question_id, trigger_feedback_count, reason)
                        VALUES (?, 3, 'THREE_STUDENT_REPORTS')
                        ON CONFLICT (question_id) DO NOTHING
                        """,
                        questionId
                );
            }
        }
        if (!inserted.isEmpty()) {
            return inserted.getFirst();
        }
        return findFeedback(id, userId);
    }

    private WrongQuestionDtos.FeedbackView findFeedback(long wrongQuestionId, long userId) {
        var rows = db.query(
                """
                SELECT id, wrong_question_id, question_id, category, comment, status, created_at
                FROM rs_question_feedback
                WHERE wrong_question_id = ? AND user_id = ?
                """,
                (r, n) -> mapFeedback(r),
                wrongQuestionId, userId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private WrongQuestionDtos.FeedbackView mapFeedback(ResultSet r) throws SQLException {
        return new WrongQuestionDtos.FeedbackView(
                String.valueOf(r.getLong("id")),
                String.valueOf(r.getLong("wrong_question_id")),
                String.valueOf(r.getLong("question_id")),
                r.getString("category"),
                r.getString("comment"),
                r.getString("status"),
                r.getObject("created_at", OffsetDateTime.class)
        );
    }

    private WrongQuestionDtos.AttemptView mapAttemptOrNull(ResultSet r, String prefix) throws SQLException {
        Long attemptId = r.getObject(prefix + "id", Long.class);
        if (attemptId == null) {
            return null;
        }
        return mapAttempt(r, prefix);
    }

    private WrongQuestionDtos.AttemptView mapAttempt(ResultSet r, String prefix) throws SQLException {
        long attemptId = r.getLong(prefix + "id");
        String attemptType = r.getString(prefix + (prefix.isEmpty() ? "attempt_type" : "type"));
        Long practiceId = r.getObject(prefix + "practice_id", Long.class);
        Long itemId = r.getObject(prefix + "item_id", Long.class);
        Long taskItemId = r.getObject(prefix + "task_item_id", Long.class);
        JsonNode answer = readNullable(r.getString(prefix + "answer"));
        JsonNode grade = readNullable(r.getString(prefix + "grade"));
        Boolean passed = r.getObject(prefix + "passed", Boolean.class);
        boolean aiJudged = grade != null && "LLM_SUGGESTED".equals(grade.path("grading_mode").asText());
        OffsetDateTime createdAt = r.getObject(prefix + "created_at", OffsetDateTime.class);
        return new WrongQuestionDtos.AttemptView(
                String.valueOf(attemptId),
                attemptType,
                practiceId == null ? null : String.valueOf(practiceId),
                itemId == null ? null : String.valueOf(itemId),
                taskItemId == null ? null : String.valueOf(taskItemId),
                answer,
                grade,
                passed,
                aiJudged,
                createdAt
        );
    }

    private JsonNode publicContent(JsonNode source) {
        if (source == null || !source.isObject()) {
            return source;
        }
        ObjectNode copy = (ObjectNode) source.deepCopy();
        copy.remove("explanation");
        return copy;
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readNullable(String value) {
        return value == null ? null : read(value);
    }

    private static long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            validation("Invalid identifier");
            return 0;
        }
    }

    private static void validation(String message) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private static void notFound() {
        throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Wrong question not found");
    }

    private record DetailRow(
            long id,
            long questionId,
            long questionVersionId,
            long knowledgeId,
            String knowledgeCode,
            String knowledgeName,
            String questionType,
            String sourceType,
            String questionStatus,
            String status,
            int wrongCount,
            OffsetDateTime firstWrongAt,
            OffsetDateTime lastWrongAt,
            OffsetDateTime masteredAt,
            Long latestAttemptId,
            String contentJson,
            String answerJson,
            String rubricJson,
            Long originWrongQuestionId
    ) {}
}
