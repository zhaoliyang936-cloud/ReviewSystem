package cn.reviewsystem.ai;

import cn.reviewsystem.mastery.MasteryJudgePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Primary
@Component
public class LlmMasteryJudge implements MasteryJudgePort {
    private static final Logger log = LoggerFactory.getLogger(LlmMasteryJudge.class);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AiProperties properties;
    private final AiQuotaService quotaService;
    private final AiJobService jobService;
    private final LlmClient llmClient;
    private final PromptTemplates prompts;
    private final AiOutputValidator validator;

    public LlmMasteryJudge(
            JdbcTemplate db,
            ObjectMapper json,
            AiProperties properties,
            AiQuotaService quotaService,
            AiJobService jobService,
            LlmClient llmClient,
            PromptTemplates prompts,
            AiOutputValidator validator) {
        this.db = db;
        this.json = json;
        this.properties = properties;
        this.quotaService = quotaService;
        this.jobService = jobService;
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.validator = validator;
    }

    @Override
    public Judgment judge(long userId, long knowledgeId, int n, BigDecimal ruleScore) {
        if (!properties.isConfigured() || ruleScore == null || n <= 0) {
            return null;
        }
        Long maxEvidenceId = db.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM rs_evidence WHERE user_id=? AND knowledge_id=?",
                Long.class,
                userId,
                knowledgeId);
        long evidenceRevision = maxEvidenceId == null ? 0L : maxEvidenceId;

        var existing = db.query(
                "SELECT id, state, output_json::text FROM rs_ai_job "
                        + "WHERE user_id=? AND knowledge_id=? AND evidence_revision=? AND scenario='MASTERY_JUDGEMENT'",
                (r, idx) -> new ExistingJob(r.getLong(1), r.getString(2), r.getString(3)),
                userId,
                knowledgeId,
                evidenceRevision);
        if (!existing.isEmpty()) {
            ExistingJob job = existing.get(0);
            if ("SUCCEEDED".equals(job.state()) && job.outputJson() != null) {
                return parseStoredJudgment(job.id(), job.outputJson());
            }
            return null;
        }

        if (!quotaService.tryConsume(userId, AiQuotaService.Bucket.MASTERY)) {
            return null;
        }

        long jobId;
        try {
            String contextHash = AiJobService.sha256(userId + ":" + knowledgeId + ":" + evidenceRevision);
            jobId = jobService.createJob(
                    userId,
                    "MASTERY_JUDGEMENT",
                    contextHash,
                    null,
                    json.createArrayNode(),
                    null,
                    knowledgeId,
                    evidenceRevision,
                    "RUNNING",
                    OffsetDateTime.now().plus(java.time.Duration.ofMillis(Math.max(1000L, properties.getMasteryTimeoutMs()))));
        } catch (DuplicateKeyException dup) {
            return null;
        }

        String summary = buildEvidenceSummary(userId, knowledgeId);
        String prompt = prompts.masteryJudgement(n, ruleScore.toPlainString(), summary);

        LlmResponse response;
        try {
            response = llmClient.complete(
                    new LlmRequest(prompt, true),
                    Duration.ofMillis(Math.max(1000L, properties.getMasteryTimeoutMs())));
        } catch (AiUnavailableException ex) {
            jobService.fail(jobId, "AI_" + ex.getFailure().name());
            log.warn("Mastery judgement job={} failed: {}", jobId, ex.getFailure());
            return null;
        }

        ObjectNode validated = validator.validateMasteryJudgement(response.content());
        if (validated == null) {
            jobService.fail(jobId, "AI_INVALID_MASTERY");
            log.warn("Mastery judgement job={} rejected: invalid output", jobId);
            return null;
        }

        jobService.succeed(jobId, validated, response.usage().toJson(json));
        BigDecimal score = validated.get("score").decimalValue();
        return new Judgment(score, validated.toString(), jobId);
    }

    private String buildEvidenceSummary(long userId, long knowledgeId) {
        List<String> lines = db.query(
                "SELECT question_type, COUNT(*), ROUND(AVG(result_y), 3), MAX(submitted_at) "
                        + "FROM rs_evidence "
                        + "WHERE user_id=? AND knowledge_id=? AND revoked=FALSE AND result_y IS NOT NULL "
                        + "GROUP BY question_type ORDER BY question_type",
                (r, idx) -> String.format("%s: count=%d, avgY=%s, latest=%s",
                        r.getString(1), r.getInt(2), r.getBigDecimal(3), r.getObject(4)),
                userId,
                knowledgeId);
        return lines.isEmpty() ? "无有效证据" : String.join("\n", lines);
    }

    private Judgment parseStoredJudgment(long jobId, String outputJson) {
        try {
            JsonNode node = json.readTree(outputJson);
            JsonNode scoreNode = node.get("score");
            if (scoreNode == null || !scoreNode.isNumber()) {
                return null;
            }
            BigDecimal score = scoreNode.decimalValue();
            if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(new BigDecimal("100")) > 0) {
                return null;
            }
            return new Judgment(score, outputJson, jobId);
        } catch (Exception e) {
            return null;
        }
    }

    private record ExistingJob(long id, String state, String outputJson) {}
}
