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
            AttemptView latestAttempt,
            String originWrongQuestionId
    ) {
        public ListItem(
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
        ) {
            this(id, questionId, questionVersionId, knowledgeId, knowledgeCode, knowledgeName,
                    questionType, sourceType, questionStatus, status, wrongCount, firstWrongAt,
                    lastWrongAt, masteredAt, stem, aiJudged, latestAttempt, null);
        }
    }

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
            FeedbackView feedback,
            String originWrongQuestionId
    ) {
        public DetailView(
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
        ) {
            this(id, questionId, questionVersionId, knowledgeId, knowledgeCode, knowledgeName,
                    questionType, sourceType, questionStatus, status, wrongCount, firstWrongAt,
                    lastWrongAt, masteredAt, aiJudged, content, correctAnswer, rubric, explanation,
                    latestAttempt, attempts, feedback, null);
        }
    }

    public record VariantJobView(long jobId, String state) {}

    public record RedoRequest(String mode, String userId, String subjectId) {}

    public record FeedbackRequest(String category, String comment, String userId) {}
}
