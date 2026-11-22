package cn.reviewsystem.ai;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.practice.GradingService;
import cn.reviewsystem.question.QuestionValidator;
import cn.reviewsystem.question.QuestionWriteRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AiOutputValidator {
    private final ObjectMapper json;
    private final QuestionValidator questionValidator;
    private final AiProperties properties;

    public AiOutputValidator(ObjectMapper json, QuestionValidator questionValidator, AiProperties properties) {
        this.json = json;
        this.questionValidator = questionValidator;
        this.properties = properties;
    }

    public record ParsedQuestion(
            String type,
            long knowledgePointId,
            ObjectNode content,
            ObjectNode answer,
            JsonNode rubric,
            String explanation,
            double maxScore,
            Set<String> trigrams) {}

    public record ValidationBatch(
            List<ParsedQuestion> accepted,
            int duplicateCount,
            int invalidCount,
            int extraDropped,
            List<String> rejectionReasons) {}

    public record SubjectiveEvaluation(double score, List<String> missingPoints, String reason) {}

    public ValidationBatch validateQuestions(
            String rawContent,
            int requestedCount,
            long expectedKnowledgePointId,
            List<String> existingStems) {
        String cleaned = stripCodeFences(rawContent);
        JsonNode root;
        try {
            root = json.readTree(cleaned);
        } catch (Exception e) {
            return new ValidationBatch(List.of(), 0, 1, 0, List.of("MALFORMED_JSON"));
        }
        if (root == null || !root.isObject()) {
            return new ValidationBatch(List.of(), 0, 1, 0, List.of("NOT_JSON_OBJECT"));
        }
        JsonNode questionsNode = root.get("questions");
        if (questionsNode == null || !questionsNode.isArray() || questionsNode.isEmpty()) {
            return new ValidationBatch(List.of(), 0, 1, 0, List.of("EMPTY_QUESTIONS"));
        }

        List<Set<String>> existingGrams = new ArrayList<>();
        for (String stem : existingStems) {
            existingGrams.add(trigrams(stem));
        }

        List<ParsedQuestion> accepted = new ArrayList<>();
        int duplicateCount = 0;
        int invalidCount = 0;
        int extraDropped = 0;
        List<String> reasons = new ArrayList<>();
        double threshold = properties.getDuplicateThreshold();

        for (JsonNode item : questionsNode) {
            if (item == null || !item.isObject()) {
                invalidCount++;
                reasons.add("ITEM_NOT_OBJECT");
                continue;
            }
            JsonNode kpNode = item.get("knowledgePointId");
            if (kpNode == null || !kpNode.canConvertToLong() || kpNode.asLong() != expectedKnowledgePointId) {
                invalidCount++;
                reasons.add("KNOWLEDGE_MISMATCH");
                continue;
            }
            String type = text(item, "type");
            JsonNode contentNode = item.get("content");
            JsonNode answerNode = item.get("answer");
            JsonNode rubricNode = item.get("rubric");
            String explanation = item.has("explanation") && item.get("explanation").isTextual()
                    ? item.get("explanation").asText()
                    : "";
            Double maxScore = item.has("maxScore") && item.get("maxScore").isNumber()
                    ? item.get("maxScore").asDouble()
                    : null;

            if (contentNode == null || !contentNode.isObject() || answerNode == null || !answerNode.isObject()) {
                invalidCount++;
                reasons.add("MISSING_CONTENT_OR_ANSWER");
                continue;
            }
            contentNode = sanitizeJsonStrings(contentNode);
            answerNode = sanitizeJsonStrings(answerNode);
            rubricNode = rubricNode == null || rubricNode.isNull() ? null : sanitizeJsonStrings(rubricNode);
            explanation = AiOutputSanitizer.sanitize(explanation);

            try {
                questionValidator.validate(new QuestionWriteRequest(
                        String.valueOf(expectedKnowledgePointId),
                        type,
                        contentNode,
                        answerNode,
                        rubricNode == null || rubricNode.isNull() ? null : rubricNode,
                        explanation,
                        null,
                        null,
                        maxScore,
                        null));
            } catch (BusinessException ex) {
                invalidCount++;
                reasons.add("VALIDATION_FAILED:" + ex.getMessage());
                continue;
            }

            String stem = contentNode.path("stem").asText("");
            if (stem.contains("如下图所示") || stem.contains("如图所示") || stem.contains("见下图") || stem.contains("见附图")) {
                invalidCount++;
                reasons.add("GRAPHICS_NOT_SUPPORTED");
                continue;
            }
            Set<String> candidateGrams = trigrams(stem);
            boolean duplicate = false;
            for (Set<String> existing : existingGrams) {
                if (jaccard(candidateGrams, existing) >= threshold) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                duplicateCount++;
                reasons.add("DUPLICATE_STEM");
                continue;
            }

            if (accepted.size() >= requestedCount) {
                extraDropped++;
                reasons.add("EXCEEDED_COUNT_TRUNCATED");
                continue;
            }

            existingGrams.add(candidateGrams);
            accepted.add(new ParsedQuestion(
                    type,
                    expectedKnowledgePointId,
                    (ObjectNode) contentNode.deepCopy(),
                    (ObjectNode) answerNode.deepCopy(),
                    rubricNode == null || rubricNode.isNull() ? null : rubricNode.deepCopy(),
                    explanation,
                    maxScore,
                    candidateGrams));
        }
        return new ValidationBatch(accepted, duplicateCount, invalidCount, extraDropped, reasons);
    }

    public SubjectiveEvaluation validateSubjectiveReview(String rawContent, double maxScore) {
        String cleaned = stripCodeFences(rawContent);
        JsonNode root;
        try {
            root = json.readTree(cleaned);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode scoreNode = root.get("score");
        if (scoreNode == null || !scoreNode.isNumber()) {
            return null;
        }
        double score = scoreNode.asDouble();
        if (!Double.isFinite(score) || score < 0 || score > maxScore) {
            return null;
        }
        JsonNode missingNode = root.get("missingPoints");
        if (missingNode == null || !missingNode.isArray()) {
            return null;
        }
        List<String> missingPoints = new ArrayList<>();
        for (JsonNode entry : missingNode) {
            if (!entry.isTextual()) {
                return null;
            }
            missingPoints.add(AiOutputSanitizer.sanitize(entry.asText()));
        }
        JsonNode reasonNode = root.get("reason");
        if (reasonNode == null || !reasonNode.isTextual()) {
            return null;
        }
        String reason = AiOutputSanitizer.sanitize(reasonNode.asText());
        if (reason.length() > 2000) {
            return null;
        }
        return new SubjectiveEvaluation(score, missingPoints, reason);
    }

    public ObjectNode validateMasteryJudgement(String rawContent) {
        String cleaned = stripCodeFences(rawContent);
        JsonNode root;
        try {
            root = json.readTree(cleaned);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode scoreNode = root.get("score");
        if (scoreNode == null || !scoreNode.isNumber()) {
            return null;
        }
        BigDecimal score = scoreNode.decimalValue();
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(new BigDecimal("100")) > 0) {
            return null;
        }
        String reason = root.path("reason").isTextual() ? AiOutputSanitizer.sanitize(root.path("reason").asText()) : "";
        if (reason.length() > 2000) {
            reason = reason.substring(0, 2000);
        }
        ObjectNode out = json.createObjectNode();
        out.put("score", score);
        out.put("reason", reason);
        out.put("aiApplied", true);
        return out;
    }

    private JsonNode sanitizeJsonStrings(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isTextual()) {
            return json.getNodeFactory().textNode(AiOutputSanitizer.sanitize(node.asText()));
        }
        if (node.isObject()) {
            ObjectNode copy = json.createObjectNode();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), sanitizeJsonStrings(e.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = json.createArrayNode();
            node.forEach(item -> copy.add(sanitizeJsonStrings(item)));
            return copy;
        }
        return node;
    }

    /**
     * Deterministically shuffles CHOICE options using a seed derived from (questionId, version)
     * and updates answer.correctOption so it still points to the original correct option text.
     */
    public static void shuffleChoiceOptions(ObjectNode content, ObjectNode answer, long questionId, int version) {
        JsonNode optionsNode = content.get("options");
        if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() < 2) {
            return;
        }
        String correctKey = answer.path("correctOption").asText("");
        List<String> texts = new ArrayList<>(optionsNode.size());
        String correctText = null;
        for (JsonNode opt : optionsNode) {
            String k = opt.path("key").asText("");
            String t = opt.path("text").asText("");
            texts.add(t);
            if (k.equals(correctKey)) {
                correctText = t;
            }
        }
        if (correctText == null) {
            return;
        }

        long seed = stableSeed(questionId, version);
        List<Integer> indices = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            indices.add(i);
        }
        Collections.shuffle(indices, new Random(seed));

        ArrayNode shuffled = content.putArray("options");
        String newCorrectKey = null;
        for (int i = 0; i < indices.size(); i++) {
            String key = String.valueOf((char) ('A' + i));
            String text = texts.get(indices.get(i));
            ObjectNode opt = shuffled.addObject();
            opt.put("key", key);
            opt.put("text", text);
            if (newCorrectKey == null && text.equals(correctText)) {
                newCorrectKey = key;
            }
        }
        if (newCorrectKey != null) {
            answer.put("correctOption", newCorrectKey);
        }
    }

    public static String stripCodeFences(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        String body = trimmed.substring(firstNewline + 1);
        int lastFence = body.lastIndexOf("```");
        if (lastFence >= 0) {
            body = body.substring(0, lastFence);
        }
        return body.trim();
    }

    public static Set<String> trigrams(String text) {
        String normalized = GradingService.normalize(text).replaceAll("\\s+", "");
        if (normalized.isEmpty()) {
            return Set.of();
        }
        if (normalized.length() < 3) {
            return Set.of(normalized);
        }
        Set<String> grams = new HashSet<>();
        for (int i = 0; i <= normalized.length() - 3; i++) {
            grams.add(normalized.substring(i, i + 3));
        }
        return grams;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = a.size() <= b.size() ? b : a;
        for (String s : smaller) {
            if (larger.contains(s)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static long stableSeed(long questionId, int version) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((questionId + ":" + version).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, 8).getLong();
        } catch (NoSuchAlgorithmException e) {
            return Objects.hash(questionId, version);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText().trim() : "";
    }
}
