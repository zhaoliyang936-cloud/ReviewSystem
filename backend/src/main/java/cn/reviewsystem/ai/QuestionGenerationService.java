package cn.reviewsystem.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuestionGenerationService {
    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationService.class);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AiProperties properties;
    private final AiQuotaService quotaService;
    private final LlmClient llmClient;
    private final PromptTemplates prompts;
    private final AiOutputValidator validator;
    private final TransactionTemplate tx;

    public QuestionGenerationService(
            JdbcTemplate db,
            ObjectMapper json,
            AiProperties properties,
            AiQuotaService quotaService,
            LlmClient llmClient,
            PromptTemplates prompts,
            AiOutputValidator validator,
            PlatformTransactionManager txManager) {
        this.db = db;
        this.json = json;
        this.properties = properties;
        this.quotaService = quotaService;
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.validator = validator;
        this.tx = new TransactionTemplate(txManager);
    }

    public record GeneratedQuestion(
            long questionId,
            long versionId,
            long knowledgeId,
            String type,
            double maxScore) {}

    public record GenerationOutcome(
            List<GeneratedQuestion> questions,
            JsonNode outputJson,
            JsonNode usageJson,
            String errorCode) {}

    public GenerationOutcome generate(
            long jobId,
            long userId,
            long knowledgeId,
            Long sourceQuestionVersionId,
            int requestedCount) {
        if (requestedCount <= 0) {
            return new GenerationOutcome(List.of(), null, null, null);
        }
        SourceContext source = loadSourceContext(knowledgeId, sourceQuestionVersionId);
        if (source == null) {
            return new GenerationOutcome(List.of(), null, null, "AI_NO_SOURCE_QUESTION");
        }

        List<String> existingStems = new ArrayList<>(loadExistingStems(knowledgeId));
        String existingPreview = formatExistingStems(existingStems);

        List<AiOutputValidator.ParsedQuestion> accepted = new ArrayList<>();
        LlmUsage totalUsage = new LlmUsage(0, 0, 0, 0);
        String lastError = null;

        int maxCalls = 2; // initial call + at most 1 retry if duplicates occurred
        for (int call = 0; call < maxCalls && accepted.size() < requestedCount; call++) {
            int needed = requestedCount - accepted.size();
            if (!quotaService.tryConsume(userId)) {
                lastError = "AI_QUOTA_EXCEEDED";
                break;
            }
            String userPrompt = prompts.questionGeneration(
                    needed,
                    source.type(),
                    knowledgeId,
                    source.knowledgeName(),
                    source.maxScore(),
                    source.questionJson(),
                    existingPreview);
            LlmResponse response;
            try {
                response = llmClient.complete(
                        new LlmRequest(userPrompt, true),
                        Duration.ofMillis(Math.max(1000L, properties.getTimeoutMs())));
            } catch (AiUnavailableException ex) {
                lastError = mapErrorCode(ex.getFailure());
                log.warn("Question generation job={} failed: {}", jobId, lastError);
                break;
            }
            totalUsage = mergeUsage(totalUsage, response.usage());

            AiOutputValidator.ValidationBatch batch = validator.validateQuestions(
                    response.content(),
                    needed,
                    knowledgeId,
                    existingStems);

            for (AiOutputValidator.ParsedQuestion pq : batch.accepted()) {
                accepted.add(pq);
                existingStems.add(pq.content().path("stem").asText(""));
            }
            if (accepted.size() >= requestedCount) {
                break;
            }
            if (batch.duplicateCount() == 0) {
                // No duplicates to retry for; either invalid output or fewer returned
                if (accepted.isEmpty()) {
                    lastError = "AI_REJECTED";
                }
                break;
            }
            if (call == maxCalls - 1 && accepted.isEmpty()) {
                lastError = "AI_REJECTED";
            }
        }

        List<GeneratedQuestion> persisted = new ArrayList<>();
        ArrayNode questionIdsNode = json.createArrayNode();
        for (AiOutputValidator.ParsedQuestion pq : accepted) {
            try {
                GeneratedQuestion gq = tx.execute(status -> persistQuestion(jobId, source.versionId(), pq));
                if (gq != null) {
                    persisted.add(gq);
                    questionIdsNode.add(gq.questionId());
                }
            } catch (Exception ex) {
                log.warn("Failed to persist generated question for job={}", jobId, ex);
            }
        }

        if (persisted.isEmpty() && lastError == null) {
            lastError = "AI_REJECTED";
        }

        ObjectNode outputJson = null;
        if (!persisted.isEmpty()) {
            outputJson = json.createObjectNode();
            outputJson.set("questionIds", questionIdsNode);
            outputJson.put("count", persisted.size());
        }
        return new GenerationOutcome(
                persisted,
                outputJson,
                totalUsage.toJson(json),
                persisted.isEmpty() ? lastError : null);
    }

    private GeneratedQuestion persistQuestion(
            long jobId,
            long originQuestionVersionId,
            AiOutputValidator.ParsedQuestion pq) {
        Long seq = db.queryForObject("SELECT nextval('rs_question_no_seq')", Long.class);
        String questionNo = "DS-%06d".formatted(seq);
        Long questionId = Objects.requireNonNull(db.queryForObject(
                "INSERT INTO rs_question(subject_id, question_no, origin, status, gen_job_id, origin_question_version_id) "
                        + "VALUES (1, ?, 'AI_GENERATED', 'ACTIVE', ?, ?) RETURNING id",
                Long.class,
                questionNo,
                jobId,
                originQuestionVersionId));

        ObjectNode contentCopy = pq.content().deepCopy();
        ObjectNode answerCopy = pq.answer().deepCopy();
        if ("CHOICE".equals(pq.type())) {
            AiOutputValidator.shuffleChoiceOptions(contentCopy, answerCopy, questionId, 1);
        }
        if (pq.explanation() != null && !pq.explanation().isBlank()) {
            contentCopy.put("explanation", pq.explanation().trim());
        } else {
            contentCopy.remove("explanation");
        }
        String rubricStr = pq.rubric() == null || pq.rubric().isNull() ? null : writeJson(pq.rubric());
        String sourceRef = "AI:" + properties.getModel() + ":" + PromptTemplates.TEMPLATE_VERSION;
        if (sourceRef.length() > 500) {
            sourceRef = sourceRef.substring(0, 500);
        }

        Long versionId = Objects.requireNonNull(db.queryForObject(
                "INSERT INTO rs_question_version(question_id, version, subject_id, knowledge_point_id, type, "
                        + "max_score, content_json, answer_json, rubric_json, source_ref) "
                        + "VALUES (?, 1, 1, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?) RETURNING id",
                Long.class,
                questionId,
                pq.knowledgePointId(),
                pq.type(),
                pq.maxScore(),
                writeJson(contentCopy),
                writeJson(answerCopy),
                rubricStr,
                sourceRef));

        db.update("UPDATE rs_question SET current_version_id=? WHERE id=?", versionId, questionId);
        return new GeneratedQuestion(questionId, versionId, pq.knowledgePointId(), pq.type(), pq.maxScore());
    }

    private SourceContext loadSourceContext(long knowledgeId, Long preferredVersionId) {
        String sql = preferredVersionId != null
                ? "SELECT v.id, v.type, v.max_score, v.content_json::text, v.answer_json::text, v.rubric_json::text, k.name "
                        + "FROM rs_question_version v JOIN rs_knowledge_point k ON k.id=v.knowledge_point_id "
                        + "WHERE v.id=?"
                : "SELECT v.id, v.type, v.max_score, v.content_json::text, v.answer_json::text, v.rubric_json::text, k.name "
                        + "FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id "
                        + "JOIN rs_knowledge_point k ON k.id=v.knowledge_point_id "
                        + "WHERE q.subject_id=1 AND q.status='ACTIVE' AND v.knowledge_point_id=? ORDER BY q.id LIMIT 1";
        Object arg = preferredVersionId != null ? preferredVersionId : knowledgeId;
        var rows = db.query(sql, (r, n) -> {
            ObjectNode qNode = json.createObjectNode();
            qNode.put("type", r.getString(2));
            qNode.put("maxScore", r.getDouble(3));
            qNode.set("content", readTree(r.getString(4)));
            qNode.set("answer", readTree(r.getString(5)));
            if (r.getString(6) != null) {
                qNode.set("rubric", readTree(r.getString(6)));
            }
            return new SourceContext(r.getLong(1), r.getString(2), r.getDouble(3), r.getString(7), writeJson(qNode));
        }, arg);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<String> loadExistingStems(long knowledgeId) {
        return db.query(
                "SELECT v.content_json->>'stem' FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id "
                        + "WHERE q.subject_id=1 AND q.status='ACTIVE' AND v.knowledge_point_id=?",
                (r, n) -> r.getString(1) == null ? "" : r.getString(1),
                knowledgeId);
    }

    private static String formatExistingStems(List<String> stems) {
        if (stems.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(stems.size(), 15);
        for (int i = 0; i < limit; i++) {
            String s = stems.get(i);
            if (s.length() > 120) {
                s = s.substring(0, 120) + "...";
            }
            sb.append("- ").append(s).append('\n');
        }
        return sb.toString();
    }

    private static LlmUsage mergeUsage(LlmUsage a, LlmUsage b) {
        if (b == null) {
            return a;
        }
        return new LlmUsage(
                a.promptTokens() + b.promptTokens(),
                a.completionTokens() + b.completionTokens(),
                a.totalTokens() + b.totalTokens(),
                a.reasoningTokens() + b.reasoningTokens());
    }

    public static String mapErrorCode(AiFailure failure) {
        return switch (failure) {
            case DISABLED, UNCONFIGURED -> "AI_DISABLED";
            case QUOTA_EXCEEDED -> "AI_QUOTA_EXCEEDED";
            case RATE_LIMITED -> "AI_RATE_LIMITED";
            case SERVER_ERROR -> "AI_SERVER_ERROR";
            case AUTH -> "AI_AUTH_FAILED";
            case BAD_REQUEST -> "AI_BAD_REQUEST";
            case TIMEOUT -> "AI_TIMEOUT";
            case UNREACHABLE -> "AI_UNREACHABLE";
            case MALFORMED -> "AI_MALFORMED";
        };
    }

    private JsonNode readTree(String s) {
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private record SourceContext(long versionId, String type, double maxScore, String knowledgeName, String questionJson) {}
}
