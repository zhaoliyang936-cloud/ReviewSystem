package cn.reviewsystem.ai;

import cn.reviewsystem.material.MaterialSearchPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CitationValidator {
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "score", "raw_score", "normalized_score", "final_score", "rule_score",
            "finalScore", "ruleScore", "mastery", "masteryScore", "recommend",
            "recommendations", "priority", "questionId", "questionNo",
            "question_id", "question_no");

    private final ObjectMapper json;
    private final JdbcTemplate db;

    public CitationValidator(ObjectMapper json, JdbcTemplate db) {
        this.json = json;
        this.db = db;
    }

    public record ValidatedTutoringOutput(
            ObjectNode outputJson,
            List<CitationDto> citations) {}

    public record CitationDto(
            String id,
            String materialVersionId,
            String title,
            String chunkId) {}

    public ValidatedTutoringOutput validateAndSanitize(
            String rawContent,
            String scenario,
            long expectedKnowledgeId,
            Map<String, MaterialSearchPort.MaterialChunkHit> candidateCitations) {
        String cleaned = AiOutputValidator.stripCodeFences(rawContent);
        JsonNode root;
        try {
            root = json.readTree(cleaned);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        if (hasForbiddenField(root)) {
            return null;
        }

        Map<String, MaterialSearchPort.MaterialChunkHit> verified = verifyAuthorizedCitations(
                expectedKnowledgeId, candidateCitations);

        JsonNode stepsNode = root.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            return null;
        }

        ObjectNode sanitizedRoot = json.createObjectNode();
        Set<String> usedCitations = new LinkedHashSet<>();

        if ("EXPLANATION".equals(scenario)) {
            ArrayNode keyConcepts = sanitizedRoot.putArray("keyConcepts");
            JsonNode kcNode = root.get("keyConcepts");
            if (kcNode != null && kcNode.isArray()) {
                for (JsonNode item : kcNode) {
                    if (item.isTextual()) {
                        String s = AiOutputSanitizer.sanitize(item.asText());
                        if (!s.isBlank()) {
                            keyConcepts.add(s);
                        }
                    }
                }
            }
        }

        ArrayNode validSteps = sanitizedRoot.putArray("steps");
        for (JsonNode step : stepsNode) {
            if (step == null || !step.isObject() || hasForbiddenField(step)) {
                continue;
            }
            List<String> stepCites = extractAndValidateCitations(step.get("citationIds"), verified.keySet());
            if (stepCites == null) {
                // Invalid or unauthorized citationId -> drop this step
                continue;
            }
            String goal = AiOutputSanitizer.sanitize(step.path("goal").asText(""));
            String action = AiOutputSanitizer.sanitize(step.path("action").asText(""));
            if (goal.isBlank() || action.isBlank()) {
                continue;
            }
            ObjectNode stepOut = validSteps.addObject();
            stepOut.put("goal", goal);
            stepOut.put("action", action);
            ArrayNode citeArr = stepOut.putArray("citationIds");
            for (String cid : stepCites) {
                citeArr.add(cid);
                usedCitations.add(cid);
            }
        }

        if (validSteps.isEmpty()) {
            return null;
        }

        ArrayNode validConfusions = sanitizedRoot.putArray("possibleConfusions");
        JsonNode confNode = root.get("possibleConfusions");
        if (confNode != null && confNode.isArray()) {
            for (JsonNode conf : confNode) {
                if (conf == null || !conf.isObject() || hasForbiddenField(conf)) {
                    continue;
                }
                List<String> confCites = extractAndValidateCitations(conf.get("citationIds"), verified.keySet());
                if (confCites == null) {
                    continue;
                }
                String text = AiOutputSanitizer.sanitize(conf.path("text").asText(""));
                if (text.isBlank()) {
                    continue;
                }
                ObjectNode confOut = validConfusions.addObject();
                confOut.put("text", text);
                confOut.put("speculative", conf.path("speculative").asBoolean(true));
                ArrayNode citeArr = confOut.putArray("citationIds");
                for (String cid : confCites) {
                    citeArr.add(cid);
                    usedCitations.add(cid);
                }
            }
        }

        if ("EXPLANATION".equals(scenario)) {
            ArrayNode selfCheck = sanitizedRoot.putArray("selfCheck");
            JsonNode scNode = root.get("selfCheck");
            if (scNode != null && scNode.isArray()) {
                for (JsonNode item : scNode) {
                    if (item.isTextual()) {
                        String s = AiOutputSanitizer.sanitize(item.asText());
                        if (!s.isBlank()) {
                            selfCheck.add(s);
                        }
                    }
                }
            }
        }

        ArrayNode topCites = sanitizedRoot.putArray("citationIds");
        List<CitationDto> citationDtos = new ArrayList<>();
        for (String cid : usedCitations) {
            topCites.add(cid);
            var hit = verified.get(cid);
            if (hit != null) {
                citationDtos.add(new CitationDto(
                        cid,
                        String.valueOf(hit.materialVersionId()),
                        hit.title(),
                        String.valueOf(hit.chunkId())));
            }
        }
        sanitizedRoot.put("sanitized", true);
        return new ValidatedTutoringOutput(sanitizedRoot, citationDtos);
    }

    private Map<String, MaterialSearchPort.MaterialChunkHit> verifyAuthorizedCitations(
            long expectedKnowledgeId,
            Map<String, MaterialSearchPort.MaterialChunkHit> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        Map<String, MaterialSearchPort.MaterialChunkHit> out = new LinkedHashMap<>();
        for (var entry : candidates.entrySet()) {
            String key = entry.getKey();
            var hit = entry.getValue();
            if (hit == null) {
                continue;
            }
            Long ok = db.queryForObject(
                    "SELECT count(*) FROM rs_material_chunk c "
                            + "JOIN rs_material_version mv ON mv.id = c.material_version_id "
                            + "WHERE c.id = ? AND mv.id = ? AND mv.subject_id = 1 "
                            + "  AND mv.knowledge_id = ? AND mv.publication_status = 'PUBLISHED' "
                            + "  AND NOT EXISTS ("
                            + "      SELECT 1 FROM rs_material_version mv3 "
                            + "      WHERE mv3.material_id = mv.material_id "
                            + "        AND mv3.version > mv.version "
                            + "        AND mv3.publication_status = 'WITHDRAWN'"
                            + "  )",
                    Long.class,
                    hit.chunkId(),
                    hit.materialVersionId(),
                    expectedKnowledgeId);
            if (ok != null && ok > 0) {
                out.put(key, hit);
            }
        }
        return out;
    }

    private static List<String> extractAndValidateCitations(JsonNode citeNode, Set<String> allowedIds) {
        if (citeNode == null || citeNode.isNull()) {
            return List.of();
        }
        if (!citeNode.isArray()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode n : citeNode) {
            if (!n.isTextual()) {
                return null;
            }
            String id = n.asText().trim();
            if (!allowedIds.contains(id)) {
                return null;
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static boolean hasForbiddenField(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (FORBIDDEN_FIELDS.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
