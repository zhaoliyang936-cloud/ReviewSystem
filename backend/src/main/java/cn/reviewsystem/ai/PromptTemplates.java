package cn.reviewsystem.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PromptTemplates {
    private final String questionGenerationTemplate;
    private final String subjectiveReviewTemplate;
    private final String masteryJudgementTemplate;
    private final String wrongVariantTemplate;
    private final String reviewGuidanceTemplate;
    private final String errorExplanationTemplate;

    public PromptTemplates() {
        this.questionGenerationTemplate = load("prompts/question_generation.txt");
        this.subjectiveReviewTemplate = load("prompts/subjective_review.txt");
        this.masteryJudgementTemplate = load("prompts/mastery_judgement.txt");
        this.wrongVariantTemplate = load("prompts/wrong_variant.txt");
        this.reviewGuidanceTemplate = load("prompts/review_guidance.txt");
        this.errorExplanationTemplate = load("prompts/error_explanation.txt");
    }

    public static String versionFor(String scenario) {
        if (scenario == null) {
            return "unknown-v1";
        }
        return switch (scenario) {
            case "QUESTION_GENERATION" -> "question-generation-v2";
            case "WRONG_VARIANT" -> "wrong-variant-v3";
            case "GUIDANCE" -> "review-guidance-v1";
            case "EXPLANATION" -> "error-explanation-v1";
            case "SUBJECTIVE_REVIEW" -> "subjective-review-v1";
            case "MASTERY_JUDGEMENT" -> "mastery-judgement-v1";
            default -> "unknown-v1";
        };
    }

    public String questionGeneration(
            int count,
            String type,
            long knowledgePointId,
            String knowledgeName,
            double maxScore,
            String sourceQuestionJson,
            String existingStems) {
        return render(questionGenerationTemplate, Map.of(
                "count", String.valueOf(count),
                "type", type,
                "knowledgePointId", String.valueOf(knowledgePointId),
                "knowledgeName", safe(knowledgeName),
                "maxScore", String.valueOf(maxScore),
                "sourceQuestionJson", safe(sourceQuestionJson),
                "existingStems", safe(existingStems)));
    }

    public String wrongVariant(
            String type,
            long knowledgePointId,
            String knowledgeName,
            double maxScore,
            String sourceQuestionJson,
            String wrongAnswerJson,
            String materialsText,
            String existingStems) {
        return render(wrongVariantTemplate, Map.of(
                "type", safe(type),
                "knowledgePointId", String.valueOf(knowledgePointId),
                "knowledgeName", safe(knowledgeName),
                "maxScore", String.valueOf(maxScore),
                "sourceQuestionJson", safe(sourceQuestionJson),
                "wrongAnswerJson", safe(wrongAnswerJson),
                "materialsText", safe(materialsText),
                "existingStems", safe(existingStems)));
    }

    public String reviewGuidance(
            String ruleFacts,
            String userQuery,
            String materialsText,
            String allowedCitationIds,
            String modeInstruction) {
        return render(reviewGuidanceTemplate, Map.of(
                "ruleFacts", safe(ruleFacts),
                "userQuery", safe(userQuery),
                "materialsText", safe(materialsText),
                "allowedCitationIds", safe(allowedCitationIds),
                "modeInstruction", safe(modeInstruction)));
    }

    public String errorExplanation(
            String questionSnapshot,
            String wrongAnswer,
            String materialsText,
            String allowedCitationIds) {
        return render(errorExplanationTemplate, Map.of(
                "questionSnapshot", safe(questionSnapshot),
                "wrongAnswer", safe(wrongAnswer),
                "materialsText", safe(materialsText),
                "allowedCitationIds", safe(allowedCitationIds)));
    }

    public String subjectiveReview(
            double maxScore,
            String stem,
            String referenceAnswer,
            String rubric,
            String submittedAnswer) {
        return render(subjectiveReviewTemplate, Map.of(
                "maxScore", String.valueOf(maxScore),
                "stem", safe(stem),
                "referenceAnswer", safe(referenceAnswer),
                "rubric", safe(rubric),
                "submittedAnswer", safe(submittedAnswer)));
    }

    public String masteryJudgement(int n, String ruleScore, String evidenceSummary) {
        return render(masteryJudgementTemplate, Map.of(
                "n", String.valueOf(n),
                "ruleScore", safe(ruleScore),
                "evidenceSummary", safe(evidenceSummary)));
    }

    private static String load(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt template: " + path, e);
        }
    }

    private static String render(String template, Map<String, String> vars) {
        String out = template;
        for (var entry : vars.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
