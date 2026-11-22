package cn.reviewsystem.ai;

import cn.reviewsystem.practice.AiAvailabilityPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SubjectiveReviewService {
    private static final Logger log = LoggerFactory.getLogger(SubjectiveReviewService.class);
    public static final String DISCLAIMER = "AI 建议分不构成正式成绩";

    private final AiAvailabilityPort availability;
    private final AiProperties properties;
    private final AiQuotaService quotaService;
    private final AiJobService jobService;
    private final LlmClient llmClient;
    private final PromptTemplates prompts;
    private final AiOutputValidator validator;
    private final ObjectMapper json;

    public SubjectiveReviewService(
            AiAvailabilityPort availability,
            AiProperties properties,
            AiQuotaService quotaService,
            AiJobService jobService,
            LlmClient llmClient,
            PromptTemplates prompts,
            AiOutputValidator validator,
            ObjectMapper json) {
        this.availability = availability;
        this.properties = properties;
        this.quotaService = quotaService;
        this.jobService = jobService;
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.validator = validator;
        this.json = json;
    }

    public record ReviewOutcome(
            String status,
            Double rawScore,
            Double normalizedScore,
            Boolean passed,
            List<String> missingPoints,
            String reason,
            String errorCode) {
        public static ReviewOutcome unavailable(String reasonCode) {
            return new ReviewOutcome("AI_REVIEW_PENDING", null, null, null, List.of(), null,
                    Objects.requireNonNull(reasonCode, "reasonCode"));
        }

        public static ReviewOutcome failed(String errorCode) {
            return new ReviewOutcome("AI_REVIEW_FAILED", null, null, null, List.of(), null,
                    Objects.requireNonNull(errorCode, "errorCode"));
        }

        public static ReviewOutcome succeeded(
                double rawScore,
                double normalizedScore,
                boolean passed,
                List<String> missingPoints,
                String reason) {
            return new ReviewOutcome("AUTO_GRADED", rawScore, normalizedScore, passed, missingPoints, reason, null);
        }
    }

    public ReviewOutcome review(
            long userId,
            Long questionVersionId,
            Long knowledgeId,
            double maxScore,
            String stem,
            JsonNode referenceAnswer,
            JsonNode rubric,
            JsonNode submitted) {
        if (!properties.isConfigured()) {
            return ReviewOutcome.unavailable("AI_DISABLED");
        }
        if (!availability.available(userId)) {
            return ReviewOutcome.unavailable("AI_QUOTA_EXCEEDED");
        }
        if (!quotaService.tryConsume(userId)) {
            return ReviewOutcome.unavailable("AI_QUOTA_EXCEEDED");
        }

        String contextHash = AiJobService.sha256(userId + ":" + questionVersionId + ":" + System.nanoTime());
        long jobId = jobService.createJob(
                userId,
                "SUBJECTIVE_REVIEW",
                contextHash,
                null,
                json.createArrayNode(),
                questionVersionId,
                knowledgeId,
                null,
                "RUNNING",
                OffsetDateTime.now().plus(Duration.ofMillis(Math.max(1000L, properties.getTimeoutMs()))));

        String prompt = prompts.subjectiveReview(
                maxScore,
                stem,
                referenceAnswer == null ? "" : referenceAnswer.toString(),
                rubric == null ? "" : rubric.toString(),
                submitted == null ? "" : submitted.toString());

        LlmResponse response;
        try {
            response = llmClient.complete(
                    new LlmRequest(prompt, true),
                    Duration.ofMillis(Math.max(1000L, properties.getTimeoutMs())));
        } catch (AiUnavailableException ex) {
            String errorCode = mapErrorCode(ex.getFailure());
            jobService.fail(jobId, errorCode);
            log.warn("Subjective review job={} failed: {}", jobId, errorCode);
            return ReviewOutcome.failed(errorCode);
        }

        AiOutputValidator.SubjectiveEvaluation eval = validator.validateSubjectiveReview(response.content(), maxScore);
        if (eval == null) {
            jobService.fail(jobId, "AI_INVALID_REVIEW");
            log.warn("Subjective review job={} rejected: invalid output", jobId);
            return ReviewOutcome.failed("AI_INVALID_REVIEW");
        }

        double normalized = BigDecimal.valueOf(eval.score() / maxScore * 100.0)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
        boolean passed = normalized >= 60.0;

        ObjectNode outputJson = json.createObjectNode();
        outputJson.put("score", eval.score());
        outputJson.put("normalizedScore", normalized);
        outputJson.put("passed", passed);
        ArrayNode mp = outputJson.putArray("missingPoints");
        eval.missingPoints().forEach(mp::add);
        outputJson.put("reason", eval.reason());
        jobService.succeed(jobId, outputJson, response.usage().toJson(json));

        return ReviewOutcome.succeeded(eval.score(), normalized, passed, eval.missingPoints(), eval.reason());
    }

    private static String mapErrorCode(AiFailure failure) {
        return QuestionGenerationService.mapErrorCode(failure);
    }
}
