package cn.reviewsystem.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PromptTemplates {
    public static final String TEMPLATE_VERSION = "M10A_V1";

    private final String questionGenerationTemplate;
    private final String subjectiveReviewTemplate;
    private final String masteryJudgementTemplate;

    public PromptTemplates() {
        this.questionGenerationTemplate = load("prompts/question_generation.txt");
        this.subjectiveReviewTemplate = load("prompts/subjective_review.txt");
        this.masteryJudgementTemplate = load("prompts/mastery_judgement.txt");
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
