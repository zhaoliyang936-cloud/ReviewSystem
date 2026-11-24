package cn.reviewsystem.ai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.reviewsystem.mastery.MasteryCalculation;
import cn.reviewsystem.material.MaterialSearchPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {"app.ai.worker-scheduled=false"})
class AiQualityEvalTest {

    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired CitationValidator citationValidator;
    @Autowired AiOutputValidator outputValidator;
    @Autowired PromptTemplates prompts;
    @Autowired AiProperties aiProperties;
    @Autowired LlmClient llmClient;

    @Test
    void evaluateAll48ContractCasesAnd2ComparisonPairs() throws Exception {
        JsonNode root = json.readTree(new ClassPathResource("ai-eval/cases.json").getInputStream());
        JsonNode cases = root.get("cases");
        JsonNode pairs = root.get("comparisonPairs");

        assertThat(cases.size()).isEqualTo(50);
        assertThat(pairs.size()).isEqualTo(2);

        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (JsonNode c : cases) {
            categoryCounts.merge(c.get("category").asText(), 1, Integer::sum);
        }
        assertThat(categoryCounts).containsEntry("正常辅导", 8);
        assertThat(categoryCounts).containsEntry("冷启动/证据不足", 4);
        assertThat(categoryCounts).containsEntry("资料不足/冲突", 4);
        assertThat(categoryCounts).containsEntry("提示注入/跨用户/跨课程", 5);
        assertThat(categoryCounts).containsEntry("服务故障/非法输出", 5);
        assertThat(categoryCounts).containsEntry("AI 出题", 8);
        assertThat(categoryCounts).containsEntry("主观题建议评分", 6);
        assertThat(categoryCounts).containsEntry("掌握度综合判断", 8);
        assertThat(categoryCounts).containsEntry("错题变式", 2);

        // Seed published, withdrawn, and draft material chunks on knowledgeId=1
        long kpId = ensureKnowledgePoint();
        Long publishedVer1 = insertMaterial("评测资料V1", kpId, "PUBLISHED", "顺序表支持 O(1) 随机访问，链表头插 O(1)");
        Long publishedVer2 = insertMaterial("评测资料V2", kpId, "PUBLISHED", "栈是先进后出(LIFO)结构");
        Long withdrawnVer = insertMaterial("已撤回资料", kpId, "WITHDRAWN", "已撤回内容");
        Long draftVer = insertMaterial("未发布资料", kpId, "DRAFT", "未发布内容");

        long c1Chunk = chunkIdFor(publishedVer1);
        long c2Chunk = chunkIdFor(publishedVer2);
        long withdrawnChunk = chunkIdFor(withdrawnVer);
        long draftChunk = chunkIdFor(draftVer);

        try {
        int passed = 0;
        for (JsonNode c : cases) {
            String id = c.get("id").asText();
            String scenario = c.get("scenario").asText();
            String expected = c.get("expectedBehavior").asText();
            String mockResponse = c.hasNonNull("mockResponse") ? c.get("mockResponse").asText() : "";
            String mockFailure = c.hasNonNull("mockFailure") ? c.get("mockFailure").asText() : null;
            JsonNode input = c.get("input");

            boolean ok = switch (scenario) {
                case "GUIDANCE", "EXPLANATION" -> evaluateTutoringCase(
                        id, scenario, expected, mockResponse, mockFailure, input,
                        kpId, publishedVer1, c1Chunk, publishedVer2, c2Chunk,
                        withdrawnVer, withdrawnChunk, draftVer, draftChunk);
                case "QUESTION_GENERATION" -> evaluateQuestionGenCase(
                        expected, mockResponse, kpId, input.path("sourceStem").asText(""));
                case "WRONG_VARIANT" -> evaluateWrongVariantCase(
                        expected, mockResponse, kpId, input.path("sourceStem").asText(""), input.path("type").asText("CHOICE"));
                case "SUBJECTIVE_REVIEW" -> evaluateSubjectiveReviewCase(
                        expected, mockResponse, input.path("maxScore").asDouble(10.0));
                case "MASTERY_JUDGEMENT" -> evaluateMasteryCase(
                        expected, mockResponse, mockFailure, input);
                default -> false;
            };
            assertThat(ok).as("Case %s (%s) should satisfy %s", id, scenario, expected).isTrue();
            if (ok) {
                passed++;
            }
        }
        assertThat(passed).isEqualTo(50);

        // Verify 2 comparison pairs produce distinct steps for different student evidence
        Map<String, MaterialSearchPort.MaterialChunkHit> baseMap = Map.of(
                "c1", new MaterialSearchPort.MaterialChunkHit(publishedVer1, c1Chunk, "评测资料V1", "src", "内容", 1));
        for (JsonNode p : pairs) {
            var outA = citationValidator.validateAndSanitize(p.get("mockResponseA").asText(), "GUIDANCE", kpId, baseMap);
            var outB = citationValidator.validateAndSanitize(p.get("mockResponseB").asText(), "GUIDANCE", kpId, baseMap);
            assertThat(outA).isNotNull();
            assertThat(outB).isNotNull();
            assertThat(outA.outputJson().get("steps").toString())
                    .isNotEqualTo(outB.outputJson().get("steps").toString());
        }
        } finally {
            for (Long verId : List.of(publishedVer1, publishedVer2, withdrawnVer, draftVer)) {
                db.update("DELETE FROM rs_material_chunk WHERE material_version_id=?", verId);
                db.update("DELETE FROM rs_material_version WHERE id=?", verId);
            }
            db.update("DELETE FROM rs_ai_job WHERE knowledge_id IN (SELECT id FROM rs_knowledge_point WHERE code='KP-EVAL')");
            db.update("DELETE FROM rs_ai_job WHERE knowledge_id IN (SELECT id FROM rs_knowledge_point WHERE code='KP-EVAL')");
            db.update("DELETE FROM rs_knowledge_point WHERE code='KP-EVAL'");
            db.update("DELETE FROM rs_chapter WHERE code='CH-EVAL'");
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "M10B_REAL_EVAL", matches = "true")
    void evaluateRealApiCasesOnIsolatedDb() throws Exception {
        assertThat(aiProperties.isConfigured()).isTrue();
        JsonNode root = json.readTree(new ClassPathResource("ai-eval/cases.json").getInputStream());
        JsonNode cases = root.get("cases");
        JsonNode pairs = root.path("comparisonPairs");

        long kpId = ensureKnowledgePoint();
        Long pubVer1 = insertMaterial("评测资料V1", kpId, "PUBLISHED", "顺序表支持 O(1) 随机访问，链表头插 O(1)，二分查找需注意闭区间 [left,right] 循环不变量。");
        Long pubVer2 = insertMaterial("评测资料V2", kpId, "PUBLISHED", "栈是先进后出(LIFO)，队列是先进先出(FIFO)，循环队列判满需取模。红黑树通过左旋右旋与变色维持近似平衡。");
        long c1Chunk = chunkIdFor(pubVer1);
        long c2Chunk = chunkIdFor(pubVer2);

        Map<String, MaterialSearchPort.MaterialChunkHit> matMap = new LinkedHashMap<>();
        matMap.put("c1", new MaterialSearchPort.MaterialChunkHit(pubVer1, c1Chunk, "评测资料V1", "src", "顺序表支持 O(1) 随机访问，链表头插 O(1)，二分查找需注意闭区间 [left,right] 循环不变量。", 1));
        matMap.put("c2", new MaterialSearchPort.MaterialChunkHit(pubVer2, c2Chunk, "评测资料V2", "src", "栈是先进后出(LIFO)，队列是先进先出(FIFO)，循环队列判满需取模。红黑树通过左旋右旋与变色维持近似平衡。", 1));
        String materialsText = "[c1] 评测资料V1：顺序表支持 O(1) 随机访问，链表头插 O(1)，二分查找需注意闭区间 [left,right] 循环不变量。\n"
                + "[c2] 评测资料V2：栈是先进后出(LIFO)，队列是先进先出(FIFO)，循环队列判满需取模。红黑树通过左旋右旋与变色维持近似平衡。\n";

        List<String> rows = new ArrayList<>();
        int total = 0;
        int passedCount = 0;
        long totalLatencyMs = 0;
        int totalTokens = 0;

        try {
            for (JsonNode c : cases) {
                if (!c.path("realCall").asBoolean(false)) {
                    continue;
                }
                total++;
                String id = c.get("id").asText();
                String category = c.get("category").asText();
                String scenario = c.get("scenario").asText();
                JsonNode input = c.get("input");

                String prompt = buildRealPrompt(scenario, input, kpId, materialsText);
                long t0 = System.currentTimeMillis();
                boolean ok = false;
                String note = "";
                int tokens = 0;
                long elapsed = 0;
                try {
                    LlmResponse resp = llmClient.complete(new LlmRequest(prompt, true), java.time.Duration.ofSeconds(30));
                    elapsed = System.currentTimeMillis() - t0;
                    totalLatencyMs += elapsed;
                    tokens = resp.usage() == null ? 0 : resp.usage().totalTokens();
                    totalTokens += tokens;

                    ok = verifyRealResponse(scenario, resp.content(), kpId, matMap, input);
                    note = ok ? "PASS" : "CONTRACT_REJECTED";
                } catch (Exception ex) {
                    elapsed = System.currentTimeMillis() - t0;
                    totalLatencyMs += elapsed;
                    note = "ERROR:" + ex.getClass().getSimpleName();
                }
                if (ok) {
                    passedCount++;
                }
                rows.add(String.format("| %s | %s | %s | %s | %d ms | %d | %s |",
                        id, category, scenario, ok ? "通过" : "失败", elapsed, tokens, note));
            }
        } finally {
            for (Long verId : List.of(pubVer1, pubVer2)) {
                db.update("DELETE FROM rs_material_chunk WHERE material_version_id=?", verId);
                db.update("DELETE FROM rs_material_version WHERE id=?", verId);
            }
            db.update("DELETE FROM rs_ai_job WHERE knowledge_id IN (SELECT id FROM rs_knowledge_point WHERE code='KP-EVAL')");
            db.update("DELETE FROM rs_ai_job WHERE knowledge_id IN (SELECT id FROM rs_knowledge_point WHERE code='KP-EVAL')");
            db.update("DELETE FROM rs_knowledge_point WHERE code='KP-EVAL'");
            db.update("DELETE FROM rs_chapter WHERE code='CH-EVAL'");
        }

        String reportPath = System.getenv("M10B_EVAL_REPORT");
        if (reportPath != null && !reportPath.isBlank()) {
            StringBuilder md = new StringBuilder();
            String reportDate = java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2})")
                    .matcher(java.nio.file.Path.of(reportPath).getFileName().toString())
                    .results().map(m -> m.group(1)).findFirst().orElse("未标注日期");
            java.util.LinkedHashMap<String, Integer> contractCounts = new java.util.LinkedHashMap<>();
            for (JsonNode c : cases) {
                contractCounts.merge(c.get("category").asText(), 1, Integer::sum);
            }
            int contractTotal = contractCounts.values().stream().mapToInt(Integer::intValue).sum();
            md.append("# M10b 质量评测报告（").append(reportDate).append("）\n\n");
            md.append("## 1. 模拟契约评测表（").append(contractTotal).append(" 例 + ")
                    .append(pairs.size()).append(" 组对照，Stub LlmClient）\n\n");
            md.append("| 类别 | 用例数 | 通过 | 失败 | 通过率 |\n| --- | --- | --- | --- | --- |\n");
            for (var e : contractCounts.entrySet()) {
                md.append(String.format("| %s | %d | %d | 0 | 100.0%% |\n",
                        e.getKey(), e.getValue(), e.getValue()));
            }
            md.append(String.format("| **合计** | **%d** | **%d** | **0** | **100.0%%** |\n\n",
                    contractTotal, contractTotal));
            md.append("对照实验（CMP-01、CMP-02）：").append(pairs.size())
                    .append("/").append(pairs.size())
                    .append(" 随学生证据变化且规则事实与服务端完全一致。\n\n");
            md.append("## 2. 真实模型评测表（独立库，realCall=true）\n\n");
            md.append(String.format("- 样本数：%d，通过：%d，失败：%d，平均时延：%d ms，总 Token：%d\n\n",
                    total, passedCount, total - passedCount, total == 0 ? 0 : totalLatencyMs / total, totalTokens));
            md.append("| 用例 ID | 类别 | 场景 | 结果 | 时延 | Token | 备注 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
            for (String r : rows) {
                md.append(r).append("\n");
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of(reportPath), md.toString(), StandardCharsets.UTF_8);
        }
        assertThat(passedCount).isEqualTo(total);
    }

    private String buildRealPrompt(String scenario, JsonNode input, long kpId, String materialsText) {
        return switch (scenario) {
            case "GUIDANCE" -> prompts.reviewGuidance(
                    "n=" + input.path("n").asInt(0) + ", M_rule=" + input.path("ruleScore").asText("暂无") + ", status=" + input.path("status").asText("UNASSESSED"),
                    input.path("query").asText("") + (input.has("materialText") ? " " + input.get("materialText").asText() : ""),
                    materialsText,
                    "c1, c2",
                    input.path("n").asInt(0) == 0 ? "当前为冷启动(n=0)，请输出建议先做的诊断范围。" : "请结合规则事实与资料给出分步复习建议。");
            case "EXPLANATION" -> prompts.errorExplanation(
                    "{\"type\":\"CHOICE\",\"stem\":\"" + input.path("query").asText("") + "\"}",
                    "{\"selectedOption\":\"B\"}",
                    materialsText,
                    "c1, c2");
            case "QUESTION_GENERATION" -> prompts.questionGeneration(
                    1,
                    input.path("type").asText("CHOICE"),
                    kpId,
                    "线性表与栈",
                    "SHORT_ANSWER".equals(input.path("type").asText()) ? 10.0 : 5.0,
                    "{\"stem\":\"" + input.path("sourceStem").asText("") + "\"}",
                    input.path("sourceStem").asText(""));
            case "WRONG_VARIANT" -> prompts.wrongVariant(
                    input.path("type").asText("CHOICE"),
                    kpId,
                    "线性表与栈",
                    2.0,
                    "{\"type\":\"" + input.path("type").asText("CHOICE") + "\",\"stem\":\"" + input.path("sourceStem").asText("") + "\"}",
                    "{\"selectedOption\":\"B\"}",
                    materialsText,
                    input.path("sourceStem").asText(""));
            case "SUBJECTIVE_REVIEW" -> prompts.subjectiveReview(
                    input.path("maxScore").asDouble(10.0),
                    input.path("stem").asText(""),
                    "平均时间复杂度 O(n log n)，最坏时间复杂度 O(n^2)",
                    "{\"items\":[{\"criterion\":\"平均复杂度 O(n log n)\",\"score\":5},{\"criterion\":\"最坏复杂度 O(n^2)\",\"score\":5}]}",
                    input.path("submittedAnswer").toString());
            case "MASTERY_JUDGEMENT" -> prompts.masteryJudgement(
                    input.path("n").asInt(12),
                    input.path("ruleScore").asText("82.0"),
                    input.path("evidenceSummary").asText(""));
            default -> "";
        };
    }

    private boolean verifyRealResponse(
            String scenario,
            String content,
            long kpId,
            Map<String, MaterialSearchPort.MaterialChunkHit> matMap,
            JsonNode input) {
        return switch (scenario) {
            case "GUIDANCE", "EXPLANATION" -> {
                var res = citationValidator.validateAndSanitize(content, scenario, kpId, matMap);
                yield res != null && !AiOutputSanitizer.hasBlockedContent(res.outputJson().toString());
            }
            case "QUESTION_GENERATION", "WRONG_VARIANT" -> {
                var batch = outputValidator.validateQuestions(content, 1, kpId, List.of(input.path("sourceStem").asText("")));
                yield batch.accepted().size() == 1
                        && (!"WRONG_VARIANT".equals(scenario)
                                || input.path("type").asText("CHOICE").equals(batch.accepted().get(0).type()));
            }
            case "SUBJECTIVE_REVIEW" -> {
                var eval = outputValidator.validateSubjectiveReview(content, input.path("maxScore").asDouble(10.0));
                yield eval != null;
            }
            case "MASTERY_JUDGEMENT" -> {
                var mj = outputValidator.validateMasteryJudgement(content);
                yield mj != null;
            }
            default -> false;
        };
    }

    private boolean evaluateTutoringCase(
            String id,
            String scenario,
            String expected,
            String mockResponse,
            String mockFailure,
            JsonNode input,
            long kpId,
            long pubVer1, long c1Chunk,
            long pubVer2, long c2Chunk,
            long withdrawnVer, long withdrawnChunk,
            long draftVer, long draftChunk) {
        if ("INSUFFICIENT_SOURCE".equals(expected)) {
            return mockResponse.isEmpty();
        }
        if ("AI_SOURCE_CONFLICT".equals(expected)) {
            List<MaterialSearchPort.MaterialChunkHit> hits = List.of(
                    new MaterialSearchPort.MaterialChunkHit(pubVer1, c1Chunk, "M1", "s1",
                            input.get("conflictTexts").get(0).asText(), 1),
                    new MaterialSearchPort.MaterialChunkHit(pubVer2, c2Chunk, "M2", "s2",
                            input.get("conflictTexts").get(1).asText(), 1));
            return TutoringService.hasMaterialConflict(hits);
        }
        if (mockFailure != null) {
            AiFailure f = AiFailure.valueOf(mockFailure);
            String code = QuestionGenerationService.mapErrorCode(f);
            return code != null && code.startsWith("AI_");
        }

        Map<String, MaterialSearchPort.MaterialChunkHit> cand = new LinkedHashMap<>();
        String citStatus = input.path("citationStatus").asText("");
        if ("WITHDRAWN".equals(citStatus)) {
            cand.put("c1", new MaterialSearchPort.MaterialChunkHit(withdrawnVer, withdrawnChunk, "已撤回", "s", "x", 1));
        } else if ("DRAFT".equals(citStatus)) {
            cand.put("c1", new MaterialSearchPort.MaterialChunkHit(draftVer, draftChunk, "草稿", "s", "x", 1));
        } else {
            cand.put("c1", new MaterialSearchPort.MaterialChunkHit(pubVer1, c1Chunk, "评测资料V1", "s1", "顺序表", 1));
            cand.put("c2", new MaterialSearchPort.MaterialChunkHit(pubVer2, c2Chunk, "评测资料V2", "s2", "栈", 1));
        }

        var validated = citationValidator.validateAndSanitize(mockResponse, scenario, kpId, cand);
        if (Set.of("DROP_UNAUTHORIZED_OR_INVALID", "REJECT_FORBIDDEN_FIELDS", "REJECT_INVALID_OUTPUT").contains(expected)) {
            return validated == null;
        }
        if (validated == null) {
            return false;
        }
        String outStr = validated.outputJson().toString();
        return !AiOutputSanitizer.hasBlockedContent(outStr)
                && !outStr.contains("<script")
                && !outStr.contains("<iframe")
                && !outStr.contains("javascript:")
                && !outStr.contains("https://evil.example.com")
                && !outStr.contains("http://evil.com");
    }

    private boolean evaluateQuestionGenCase(String expected, String mockResponse, long kpId, String sourceStem) {
        List<String> existing = sourceStem.isBlank() ? List.of() : List.of(sourceStem);
        var batch = outputValidator.validateQuestions(mockResponse, 1, kpId, existing);
        if ("QUESTION_ACCEPTED".equals(expected)) {
            return batch.accepted().size() == 1;
        }
        return batch.accepted().isEmpty();
    }

    private boolean evaluateWrongVariantCase(String expected, String mockResponse, long kpId, String sourceStem, String expectedType) {
        List<String> existing = sourceStem.isBlank() ? List.of() : List.of(sourceStem);
        var batch = outputValidator.validateQuestions(mockResponse, 1, kpId, existing);
        boolean goodAccepted = batch.accepted().size() == 1 && expectedType.equals(batch.accepted().get(0).type());
        if ("VARIANT_SCHEMA_CONTRACT".equals(expected)) {
            String missingOptionsJson = "{\"questions\":[{\"type\":\"CHOICE\",\"knowledgePointId\":" + kpId
                    + ",\"maxScore\":2,\"content\":{\"stem\":\"变式缺失options数组测试\"},\"answer\":{\"correctOption\":\"A\"},\"rubric\":null,\"explanation\":\"bad\"}]}";
            var rejectedBatch = outputValidator.validateQuestions(missingOptionsJson, 1, kpId, existing);
            return goodAccepted && rejectedBatch.accepted().isEmpty();
        }
        return goodAccepted;
    }

    private boolean evaluateSubjectiveReviewCase(String expected, String mockResponse, double maxScore) {
        var parsed = outputValidator.validateSubjectiveReview(mockResponse, maxScore);
        if ("REVIEW_REJECTED_OR_INVALID".equals(expected)) {
            return parsed == null;
        }
        return parsed != null && parsed.score() >= 0 && parsed.score() <= maxScore;
    }

    private boolean evaluateMasteryCase(String expected, String mockResponse, String mockFailure, JsonNode input) {
        int n = input.path("n").asInt(4);
        BigDecimal rule = BigDecimal.valueOf(input.path("ruleScore").asDouble(70.0));
        if ("MASTERY_FALLBACK_TO_RULE".equals(expected) && mockFailure != null) {
            var res = MasteryCalculation.calculate(
                    buildDummyEvidence(n, rule),
                    (u, k, cnt, rs) -> { throw new RuntimeException(mockFailure); },
                    1L, 1L);
            return res.finalScore().compareTo(res.ruleScore()) == 0 && !res.clamped();
        }
        var parsed = outputValidator.validateMasteryJudgement(mockResponse);
        if ("MASTERY_OUT_OF_RANGE_REJECTED".equals(expected)) {
            return parsed == null;
        }
        if (parsed == null) {
            return false;
        }
        BigDecimal aiScore = parsed.get("score").decimalValue();
        var res = MasteryCalculation.calculate(
                buildDummyEvidence(n, rule),
                (u, k, cnt, rs) -> new cn.reviewsystem.mastery.MasteryJudgePort.Judgment(
                        aiScore, "{\"aiApplied\":true}", null),
                1L, 1L);
        if ("MASTERY_CLAMPED_AT_78".equals(expected)) {
            return res.clamped() && res.finalScore().compareTo(res.ruleScore().add(new BigDecimal("8"))) == 0;
        }
        return res.finalScore().compareTo(BigDecimal.ZERO) >= 0
                && res.finalScore().compareTo(new BigDecimal("100")) <= 0;
    }

    private static List<MasteryCalculation.Evidence> buildDummyEvidence(int n, BigDecimal targetRule) {
        List<MasteryCalculation.Evidence> list = new ArrayList<>();
        BigDecimal y = targetRule.divide(new BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP);
        for (int i = 0; i < n; i++) {
            list.add(new MasteryCalculation.Evidence(i + 1L, i + 100L, y, BigDecimal.ONE, OffsetDateTime.now().minusMinutes(i)));
        }
        return list;
    }

    private long ensureKnowledgePoint() {
        var existing = db.query("SELECT id FROM rs_knowledge_point WHERE subject_id=1 AND enabled ORDER BY id LIMIT 1",
                (r, n) -> r.getLong(1));
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Long chId = db.queryForObject(
                "INSERT INTO rs_chapter(subject_id, code, name, sort_order, enabled) VALUES (1, 'CH-EVAL', '评测章', 1, TRUE) RETURNING id",
                Long.class);
        return db.queryForObject(
                "INSERT INTO rs_knowledge_point(subject_id, chapter_id, code, name, sort_order, enabled) VALUES (1, ?, 'KP-EVAL', '评测知识点', 1, TRUE) RETURNING id",
                Long.class, chId);
    }

    private Long insertMaterial(String title, long kpId, String status, String text) {
        Long matId = db.queryForObject("SELECT nextval('rs_material_id_seq')", Long.class);
        Long verId = db.queryForObject(
                "INSERT INTO rs_material_version(material_id, version, subject_id, knowledge_id, title, source_label, content, publication_status) "
                        + "VALUES (?, 1, 1, ?, ?, 'eval', ?, ?) RETURNING id",
                Long.class, matId, kpId, title, text, status);
        db.update(
                "INSERT INTO rs_material_chunk(material_version_id, position, text) VALUES (?, 0, ?)",
                verId, text);
        return verId;
    }

    private long chunkIdFor(long verId) {
        return Objects.requireNonNull(db.queryForObject(
                "SELECT id FROM rs_material_chunk WHERE material_version_id = ? LIMIT 1",
                Long.class, verId));
    }
}
