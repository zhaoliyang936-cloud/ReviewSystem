package cn.reviewsystem.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@TestPropertySource(properties = {"app.ai.worker-scheduled=false"})
class QuestionGenerationRetryTest {

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AiProperties properties;
    @Autowired AiQuotaService quotaService;
    @Autowired PromptTemplates prompts;
    @Autowired AiOutputValidator validator;
    @Autowired PlatformTransactionManager txManager;
    @Autowired AiJobService jobService;

    private record Seed(long userId, long chId, long kpId, long sourceQId, long sourceVerId) {}

    private Seed seedData(String suffix) {
        Long userId = db.queryForObject(
                "INSERT INTO rs_user(username, password_hash, role, enabled) VALUES (?, 'x', 'STUDENT', TRUE) RETURNING id",
                Long.class, "retry_u_" + suffix);
        Long chId = db.queryForObject(
                "INSERT INTO rs_chapter(subject_id, code, name, sort_order, enabled) VALUES (1, ?, ?, 98, TRUE) RETURNING id",
                Long.class, "CH-RT-" + suffix, "重试测试章_" + suffix);
        Long kpId = db.queryForObject(
                "INSERT INTO rs_knowledge_point(subject_id, chapter_id, code, name, sort_order, enabled) VALUES (1, ?, ?, ?, 98, TRUE) RETURNING id",
                Long.class, chId, "KP-RT-" + suffix, "重试测试知识点_" + suffix);
        Long sourceQId = db.queryForObject(
                "INSERT INTO rs_question(subject_id, question_no, origin, status) VALUES (1, ?, 'MANUAL', 'ACTIVE') RETURNING id",
                Long.class, "RT-SRC-" + suffix);
        String content = """
                {"stem":"源题：下列关于栈的叙述正确的是？","options":[{"key":"A","text":"先进后出"},{"key":"B","text":"先进先出"},{"key":"C","text":"随机存取"},{"key":"D","text":"双向链表"}],"explanation":"栈是LIFO"}
                """;
        Long sourceVerId = db.queryForObject(
                "INSERT INTO rs_question_version(question_id, version, subject_id, knowledge_point_id, type, max_score, content_json, answer_json, source_ref) "
                        + "VALUES (?, 1, 1, ?, 'CHOICE', 5.0, CAST(? AS jsonb), CAST('{\"correct\":\"A\"}' AS jsonb), 'RT_SEED') RETURNING id",
                Long.class, sourceQId, kpId, content);
        db.update("UPDATE rs_question SET current_version_id=? WHERE id=?", sourceVerId, sourceQId);
        return new Seed(userId, chId, kpId, sourceQId, sourceVerId);
    }

    private void cleanup(Seed s, long jobId) {
        db.update("DELETE FROM rs_ai_job WHERE id=?", jobId);
        db.update("UPDATE rs_question SET current_version_id=NULL, origin_question_version_id=NULL WHERE gen_job_id=? OR id=?", jobId, s.sourceQId());
        db.update("DELETE FROM rs_question_version WHERE question_id IN (SELECT id FROM rs_question WHERE gen_job_id=? OR id=?)", jobId, s.sourceQId());
        db.update("DELETE FROM rs_question WHERE gen_job_id=? OR id=?", jobId, s.sourceQId());
        db.update("DELETE FROM rs_knowledge_point WHERE id=?", s.kpId());
        db.update("DELETE FROM rs_chapter WHERE id=?", s.chId());
        db.update("DELETE FROM rs_ai_quota WHERE scope_key LIKE ?", "user:" + s.userId() + "%");
        db.update("DELETE FROM rs_user WHERE id=?", s.userId());
    }

    @Test
    void retryOnMalformedJsonSwitchesToBatch1AndSucceeds() {
        Seed s = seedData("1");
        long jobId = 99001L;
        AtomicInteger calls = new AtomicInteger(0);
        LlmClient stubClient = (req, timeout) -> {
            int c = calls.getAndIncrement();
            if (c == 0) {
                return new LlmResponse("{broken json truncated...", new LlmUsage(100, 4096, 4196, 1000), "length");
            }
            if (c == 1) {
                String q1 = """
                        {"questions":[{"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"栈顶指针top初始为-1，入栈操作应执行？","options":[{"key":"A","text":"top++再存"},{"key":"B","text":"先存再top++"},{"key":"C","text":"top--"},{"key":"D","text":"不变"}]},"answer":{"correctOption":"A"},"rubric":null,"explanation":"先移动指针再存入","maxScore":5.0}]}
                        """.formatted(s.kpId());
                return new LlmResponse(q1, new LlmUsage(100, 300, 400, 50), "stop");
            }
            String q2 = """
                    {"questions":[
                      {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"顺序栈判满条件（容量为N，top从0开始）是？","options":[{"key":"A","text":"top==N"},{"key":"B","text":"top==0"},{"key":"C","text":"top==-1"},{"key":"D","text":"top==N-1"}]},"answer":{"correctOption":"A"},"rubric":null,"explanation":"top等于N时满","maxScore":5.0},
                      {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"若进栈序列为1,2,3，不可能的出栈序列是？","options":[{"key":"A","text":"3,1,2"},{"key":"B","text":"3,2,1"},{"key":"C","text":"2,3,1"},{"key":"D","text":"1,2,3"}]},"answer":{"correctOption":"A"},"rubric":null,"explanation":"3先出则2必须在1前","maxScore":5.0}
                    ]}
                    """.formatted(s.kpId(), s.kpId());
            return new LlmResponse(q2, new LlmUsage(100, 500, 600, 80), "stop");
        };

        try {
            var svc = new QuestionGenerationService(db, json, properties, quotaService, stubClient, prompts, validator, txManager);
            var outcome = svc.generate(jobId, s.userId(), s.kpId(), s.sourceVerId(), 4);
            assertThat(outcome.errorCode()).isNull();
            assertThat(outcome.questions()).hasSize(3);
            assertThat(calls.get()).isEqualTo(3);
            assertThat(outcome.usageJson().path("completion_tokens").asInt()).isEqualTo(4896);
        } finally {
            cleanup(s, jobId);
        }
    }

    @Test
    void twoConsecutiveMalformedRoundsFailAndPersistUsageJson() {
        Seed s = seedData("2");
        long jobId = jobService.createJob(
                s.userId(), "QUESTION_GENERATION", "ctx-rt-2", null,
                json.createObjectNode().put("gap", 4), json.createObjectNode(), s.sourceVerId(), s.kpId(),
                1L, 0, "RUNNING", OffsetDateTime.now().plusMinutes(5));
        LlmClient stubClient = (req, timeout) ->
                new LlmResponse("{still broken json", new LlmUsage(120, 800, 920, 200), "length");

        try {
            var svc = new QuestionGenerationService(db, json, properties, quotaService, stubClient, prompts, validator, txManager);
            var outcome = svc.generate(jobId, s.userId(), s.kpId(), s.sourceVerId(), 4);
            assertThat(outcome.questions()).isEmpty();
            assertThat(outcome.errorCode()).isEqualTo("AI_REJECTED");
            assertThat(outcome.usageJson()).isNotNull();

            jobService.fail(jobId, outcome.errorCode(), outcome.usageJson());
            Map<String, Object> row = db.queryForMap(
                    "SELECT state, error_code, usage_json::text AS usage_json FROM rs_ai_job WHERE id=?",
                    jobId);
            System.out.println("FAILED_JOB_SQL_ROW=" + row);
            assertThat(row.get("state")).isEqualTo("FAILED");
            assertThat(row.get("error_code")).isEqualTo("AI_REJECTED");
            assertThat(row.get("usage_json")).isNotNull();
            assertThat(row.get("usage_json").toString()).contains("1600");
        } finally {
            cleanup(s, jobId);
        }
    }
}
