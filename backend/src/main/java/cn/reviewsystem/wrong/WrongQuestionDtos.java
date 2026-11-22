package cn.reviewsystem.wrong;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

public final class WrongQuestionDtos {
    private WrongQuestionDtos() {}

    public record AttemptView(
            String id,
            String attemptType,
            String practiceId,
            String itemId,
            String taskItemId,
            JsonNode answer,
            JsonNode grade,
            Boolean passed,
            boolean aiJudged,
            OffsetDateTime createdAt
    ) {}

    public record ListItem(
            String id,
            String questionId,
            String questionVersionId,
            String knowledgeId,
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
            String stem,
            boolean aiJudged,
            AttemptView latestAttempt
    ) {}

    public record FeedbackView(
            String id,
            String wrongQuestionId,
            String questionId,
            String category,
            String comment,
            String status,
            OffsetDateTime createdAt
    ) {}

    public record DetailView(
            String id,
            String questionId,
            String questionVersionId,
            String knowledgeId,
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
            boolean aiJudged,
            JsonNode content,
            JsonNode correctAnswer,
            JsonNode rubric,
            String explanation,
            AttemptView latestAttempt,
            List<AttemptView> attempts,
            FeedbackView feedback
    ) {}

    public record RedoRequest(String mode, String userId, String subjectId) {}

    public record FeedbackRequest(String category, String comment, String userId) {}
}
