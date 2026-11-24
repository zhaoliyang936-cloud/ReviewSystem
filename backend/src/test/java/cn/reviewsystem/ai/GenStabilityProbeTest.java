package cn.reviewsystem.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {"app.ai.worker-scheduled=false"})
class GenStabilityProbeTest {

    private static final int TRIALS = 10;
    private static final int REQUESTED_COUNT = 4;

    @Autowired JdbcTemplate db;
    @Autowired AiProperties aiProperties;
    @Autowired QuestionGenerationService questionGenerationService;

    @Test
    @EnabledIfEnvironmentVariable(named = "GEN_PROBE", matches = "true")
    void probeQuestionGenerationStability() throws Exception {
        assertThat(aiProperties.isConfigured())
                .as("AI must be configured (AI_ENABLED=true, AI_BASE_URL, AI_API_KEY, AI_MODEL)")
                .isTrue();

        Long userId = db.queryForObject(
                "INSERT INTO rs_user(username, password_hash, role, enabled) "
                        + "VALUES ('probe_user_' || nextval('rs_question_no_seq'), 'x', 'STUDENT', TRUE) RETURNING id",
                Long.class);
        Long chId = db.queryForObject(
                "INSERT INTO rs_chapter(subject_id, code, name, sort_order, enabled) "
                        + "VALUES (1, 'CH-PROBE', '图与最短路径', 99, TRUE) RETURNING id",
                Long.class);
        Long kpId = db.queryForObject(
                "INSERT INTO rs_knowledge_point(subject_id, chapter_id, code, name, sort_order, enabled) "
                        + "VALUES (1, ?, 'KP-PROBE', 'Dijkstra与Floyd最短路径算法', 99, TRUE) RETURNING id",
                Long.class, chId);

        String longStem = "在一个带权有向图 G=(V, E) 中，共有 n 个顶点和 m 条有向边，所有边的权值均为非负实数。"
                + "某同学希望使用 Dijkstra 算法求解从源点 s 出发到其余各顶点的单源最短路径，并采用邻接表存储图结构、"
                + "使用二叉小根堆（优先队列）维护当前尚未确定最短路径的顶点集合。在算法执行过程中，每次从堆顶弹出距离最小的顶点 u，"
                + "并对其所有出边 (u, v) 执行松弛操作：若 dist[u] + w(u, v) < dist[v]，则更新 dist[v] 并将新的距离信息插入小根堆中（不删除堆中旧的冗余节点）。"
                + "假设图 G 是连通的且 m >= n，关于该实现方式在最坏情况下的时间复杂度、空间复杂度以及正确性条件，下列哪一项表述是准确的？";
        assertThat(longStem.length()).isGreaterThanOrEqualTo(250);

        String contentJson = """
                {
                  "stem": "%s",
                  "options": [
                    {"key": "A", "text": "时间复杂度为 O((n + m) log m)，空间复杂度为 O(n + m)，要求边权非负"},
                    {"key": "B", "text": "时间复杂度为 O(n^2)，空间复杂度为 O(n)，可正确处理负权边"},
                    {"key": "C", "text": "时间复杂度为 O(m log n)，空间复杂度为 O(n)，但无法处理有向环"},
                    {"key": "D", "text": "时间复杂度为 O(nm)，空间复杂度为 O(m)，要求图必须为有向无环图"}
                  ],
                  "explanation": "使用懒惰删除的小根堆实现 Dijkstra，每条边最多入堆一次，堆大小 O(m)，总时间 O((n+m)log m)，且要求边权非负。"
                }
                """.formatted(longStem.replace("\"", "\\\""));
        String answerJson = "{\"correct\":\"A\"}";

        Long sourceQId = db.queryForObject(
                "INSERT INTO rs_question(subject_id, question_no, origin, status) "
                        + "VALUES (1, 'PROBE-SRC-1', 'MANUAL', 'ACTIVE') RETURNING id",
                Long.class);
        Long sourceVerId = db.queryForObject(
                "INSERT INTO rs_question_version(question_id, version, subject_id, knowledge_point_id, type, max_score, content_json, answer_json, source_ref) "
                        + "VALUES (?, 1, 1, ?, 'CHOICE', 5.0, CAST(? AS jsonb), CAST(? AS jsonb), 'PROBE_SEED') RETURNING id",
                Long.class, sourceQId, kpId, contentJson, answerJson);
        db.update("UPDATE rs_question SET current_version_id=? WHERE id=?", sourceVerId, sourceQId);

        int successCount = 0;
        long totalCompletion = 0;
        long totalReasoning = 0;
        int usageSamples = 0;
        Map<String, Integer> errorDist = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();

        for (int i = 1; i <= TRIALS; i++) {
            QuestionGenerationService.GenerationOutcome outcome =
                    questionGenerationService.generate(i, userId, kpId, sourceVerId, REQUESTED_COUNT);

            int produced = outcome.questions().size();
            boolean ok = produced > 0 && outcome.errorCode() == null;
            if (ok) {
                successCount++;
            } else {
                String code = outcome.errorCode() == null ? "UNKNOWN" : outcome.errorCode();
                errorDist.merge(code, 1, Integer::sum);
            }

            JsonNode usage = outcome.usageJson();
            int completion = usage != null ? usage.path("completion_tokens").asInt(0) : 0;
            int reasoning = usage != null ? usage.path("reasoning_tokens").asInt(0) : 0;
            if (usage != null && (completion > 0 || reasoning > 0)) {
                totalCompletion += completion;
                totalReasoning += reasoning;
                usageSamples++;
            }

            rows.add(String.format("| %d | %s | %d | %d | %d | %s |",
                    i,
                    ok ? "SUCCESS" : "FAIL",
                    completion,
                    reasoning,
                    produced,
                    outcome.errorCode() == null ? "-" : outcome.errorCode()));

            // Clean up generated questions so each trial starts from the identical 1-question state
            db.update("UPDATE rs_question SET current_version_id=NULL WHERE gen_job_id=?", (long) i);
            db.update("DELETE FROM rs_question_version WHERE question_id IN (SELECT id FROM rs_question WHERE gen_job_id=?)", (long) i);
            db.update("DELETE FROM rs_question WHERE gen_job_id=?", (long) i);
        }

        double successRate = (successCount * 100.0) / TRIALS;
        double avgCompletion = usageSamples == 0 ? 0.0 : (double) totalCompletion / usageSamples;
        double avgReasoning = usageSamples == 0 ? 0.0 : (double) totalReasoning / usageSamples;

        StringBuilder md = new StringBuilder();
        md.append("# AI 出题稳定性探针报告\n\n");
        md.append(String.format("- 模型：`%s`\n", aiProperties.getModel()));
        md.append(String.format("- 配置：`timeoutMs=%d`, `maxOutputTokens=%d`\n",
                aiProperties.getTimeoutMs(), aiProperties.getMaxOutputTokens()));
        md.append(String.format("- 轮次：%d（每轮请求 %d 题，源题干长度 %d 字）\n",
                TRIALS, REQUESTED_COUNT, longStem.length()));
        md.append(String.format("- 成功率：**%d/%d (%.1f%%)**\n", successCount, TRIALS, successRate));
        md.append(String.format("- completion_tokens 均值：**%.1f**\n", avgCompletion));
        md.append(String.format("- reasoning_tokens 均值：**%.1f**\n", avgReasoning));
        md.append(String.format("- 失败码分布：`%s`\n\n", errorDist.isEmpty() ? "无" : errorDist.toString()));
        md.append("| 轮次 | 结果 | completion | reasoning | 产题数 | 失败码 |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (String r : rows) {
            md.append(r).append("\n");
        }

        String reportPath = System.getenv("GEN_PROBE_REPORT");
        if (reportPath != null && !reportPath.isBlank()) {
            Path p = Path.of(reportPath);
            Files.createDirectories(p.getParent());
            Files.writeString(p, md.toString(), StandardCharsets.UTF_8);
        }

        assertThat(successCount)
                .as("Probe successCount=%d/%d, errorDist=%s", successCount, TRIALS, errorDist)
                .isGreaterThanOrEqualTo(9);
    }
}
