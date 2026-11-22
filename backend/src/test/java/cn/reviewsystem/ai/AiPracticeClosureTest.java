package cn.reviewsystem.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.reviewsystem.mastery.MasteryCalculation;
import cn.reviewsystem.mastery.MasteryEventConsumer;
import cn.reviewsystem.practice.AiAvailabilityPort;
import cn.reviewsystem.practice.NoAiAvailabilityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "app.ai.worker-scheduled=false",
        "app.ai.timeout-ms=1500",
        "app.ai.mastery-timeout-ms=1000",
        "app.ai.max-attempts=2",
        "app.ai.daily-call-limit=200",
        "app.ai.duplicate-threshold=0.85",
        "app.answer-assets.directory=target/test-answer-assets-m10a"
})
@AutoConfigureMockMvc
class AiPracticeClosureTest {
    private static HttpServer stubServer;
    private static int stubPort;
    private static final AtomicReference<StubHandler> stubHandler = new AtomicReference<>();

    @FunctionalInterface
    interface StubHandler {
        StubResponse handle(String requestBody, int callIndex) throws Exception;
    }

    record StubResponse(int status, String body, long delayMs) {
        StubResponse(int status, String body) { this(status, body, 0); }
    }

    @BeforeAll
    static void startStubServer() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubPort = stubServer.getAddress().getPort();
        AtomicInteger callSeq = new AtomicInteger(0);
        stubServer.createContext("/v1/chat/completions", (HttpExchange ex) -> {
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int idx = callSeq.incrementAndGet();
            StubResponse resp;
            try {
                StubHandler h = stubHandler.get();
                resp = h != null ? h.handle(reqBody, idx) : new StubResponse(500, "{\"error\":\"no handler\"}");
                if (resp.delayMs() > 0) {
                    Thread.sleep(resp.delayMs());
                }
            } catch (Exception e) {
                resp = new StubResponse(500, "{\"error\":\"stub error\"}");
            }
            byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(resp.status(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        stubServer.start();
    }

    @AfterAll
    static void stopStubServer() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    @DynamicPropertySource
    static void registerAiProps(DynamicPropertyRegistry registry) {
        registry.add("app.ai.enabled", () -> "true");
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + stubPort + "/v1");
        registry.add("app.ai.api-key", () -> "sk-test-secret-key-for-m10a-never-log");
        registry.add("app.ai.model", () -> "deepseek-ai/DeepSeek-V4.1-Flash");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AiJobWorker worker;
    @Autowired AiJobService jobService;
    @Autowired AiOutputValidator outputValidator;
    @Autowired MasteryEventConsumer masteryConsumer;
    @Autowired LlmMasteryJudge masteryJudge;
    @Autowired AiProperties aiProperties;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    final String prefix = "m10a" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    final String password = "valid-password-123";
    Cookie userA, userB;
    long userAId, userBId, chapterId, kpId;

    @BeforeEach
    void setup() throws Exception {
        var encoder = new BCryptPasswordEncoder();
        userAId = insertUser(prefix + "a", encoder);
        userBId = insertUser(prefix + "b", encoder);
        userA = login(prefix + "a");
        userB = login(prefix + "b");
        int n = 100 + Math.floorMod(prefix.hashCode(), 800);
        chapterId = db.queryForObject("INSERT INTO rs_chapter(subject_id,code,name,sort_order) VALUES (1,?,?,?) RETURNING id", Long.class, "M10A" + n, prefix + "ch", n);
        kpId = db.queryForObject("INSERT INTO rs_knowledge_point(subject_id,chapter_id,code,name,sort_order) VALUES (1,?,?,?,1) RETURNING id", Long.class, chapterId, "M10A" + n + "-KP", prefix + "kp");
        aiProperties.setEnabled(true);
        aiProperties.setDailyCallLimit(200);
        aiProperties.setDuplicateThreshold(0.85);
    }

    @AfterEach
    void cleanup() {
        stubHandler.set(null);
        aiProperties.setEnabled(true);
        db.update("DELETE FROM rs_ai_quota WHERE scope_key IN (?,?)", "user:" + userAId, "user:" + userBId);
        db.update("DELETE FROM rs_question_feedback WHERE user_id IN (?,?)", userAId, userBId);
        db.update("UPDATE rs_wrong_question SET latest_attempt_id=NULL WHERE user_id IN (?,?)", userAId, userBId);
        db.update("DELETE FROM rs_wrong_attempt WHERE wrong_question_id IN (SELECT id FROM rs_wrong_question WHERE user_id IN (?,?))", userAId, userBId);
        db.update("DELETE FROM rs_wrong_question WHERE user_id IN (?,?)", userAId, userBId);
        db.update("DELETE FROM rs_answer_asset WHERE user_id IN (?,?)", userAId, userBId);
        db.update("DELETE FROM rs_change_event WHERE source_id IN (SELECT id FROM rs_practice WHERE user_id IN (?,?))", userAId, userBId);
        db.update("DELETE FROM rs_change_event WHERE event_type='TASK_SUBMITTED' AND source_id IN (SELECT id FROM rs_review_task WHERE user_id IN (?,?))", userAId, userBId);
        db.update("DELETE FROM rs_mastery_current WHERE user_id IN (?,?)", userAId, userBId);
        db.update("DELETE FROM rs_mastery_snapshot WHERE user_id IN (?,?)", userAId, userBId);
        db.update("DELETE FROM rs_evidence WHERE user_id IN (?,?)", userAId, userBId);
        db.update("DELETE FROM rs_student_course_guard WHERE user_id IN (?,?)", userAId, userBId);
        db.update("DELETE FROM rs_task_item WHERE task_id IN (SELECT id FROM rs_review_task WHERE user_id IN (?,?))", userAId, userBId);
        db.update("DELETE FROM rs_review_task WHERE user_id IN (?,?)", userAId, userBId);
        db.update("DELETE FROM rs_practice_item WHERE practice_id IN (SELECT id FROM rs_practice WHERE user_id IN (?,?))", userAId, userBId);
        db.update("DELETE FROM rs_practice WHERE user_id IN (?,?)", userAId, userBId);
        db.update("DELETE FROM rs_learning_activity WHERE user_id IN (?,?)", userAId, userBId);
        List<Long> aiQIds = db.query("SELECT id FROM rs_question WHERE gen_job_id IN (SELECT id FROM rs_ai_job WHERE user_id IN (?,?))", (r, i) -> r.getLong(1), userAId, userBId);
        List<Long> prefixQIds = db.query("SELECT id FROM rs_question WHERE question_no LIKE ?", (r, i) -> r.getLong(1), prefix + "%");
        Set<Long> allQIds = new LinkedHashSet<>(aiQIds);
        allQIds.addAll(prefixQIds);
        for (long qid : allQIds) {
            db.update("UPDATE rs_question SET current_version_id=NULL, gen_job_id=NULL, origin_question_version_id=NULL WHERE id=?", qid);
        }
        db.update("DELETE FROM rs_ai_job WHERE user_id IN (?,?)", userAId, userBId);
        for (long qid : allQIds) {
            db.update("DELETE FROM rs_question_version WHERE question_id=?", qid);
            db.update("DELETE FROM rs_question WHERE id=?", qid);
        }
        db.update("DELETE FROM rs_knowledge_point WHERE chapter_id=?", chapterId);
        db.update("DELETE FROM rs_chapter WHERE id=?", chapterId);
        db.update("DELETE FROM rs_session WHERE user_id IN (?,?)", userAId, userBId);
        db.update("DELETE FROM rs_user WHERE id IN (?,?)", userAId, userBId);
    }

    // 1. 正常生成 3 题
    @Test
    void test01_normalGenerate3Questions() throws Exception {
        seedBankChoices(10);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson(threeValidChoiceQuestions(kpId))));
        JsonNode created = createPractice(userA, prefix + "t01", 10, 202);
        assertEquals("PREPARING", created.get("status").asText());
        worker.drainOnce();

        List<Map<String, Object>> aiQs = db.queryForList(
                "SELECT origin, gen_job_id, origin_question_version_id, current_version_id FROM rs_question WHERE gen_job_id IN (SELECT id FROM rs_ai_job WHERE user_id=?)",
                userAId);
        assertEquals(3, aiQs.size());
        for (var row : aiQs) {
            assertEquals("AI_GENERATED", row.get("origin"));
            assertNotNull(row.get("gen_job_id"));
            assertNotNull(row.get("origin_question_version_id"));
            assertNotNull(row.get("current_version_id"));
        }
    }

    // 2. 10 题组卷
    @Test
    void test02_tenQuestionMix7to3() throws Exception {
        seedBankChoices(10);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson(threeValidChoiceQuestions(kpId))));
        JsonNode created = createPractice(userA, prefix + "t02", 10, 202);
        worker.drainOnce();

        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("READY", detail.get("status").asText());
        assertEquals(10, detail.get("questionCount").asInt());
        assertEquals(10, detail.get("items").size());
        assertEquals(7, detail.at("/mixRatio/planned/bank").asInt());
        assertEquals(3, detail.at("/mixRatio/planned/ai").asInt());
        assertEquals(3, detail.at("/mixRatio/actual/ai").asInt());
        assertEquals(0, detail.at("/mixRatio/degraded").size());
    }

    // 3. 500 -> 降级
    @Test
    void test03_http500DegradesToBank() throws Exception {
        seedBankChoices(10);
        stubHandler.set((req, idx) -> new StubResponse(500, "{\"error\":\"internal\"}"));
        JsonNode created = createPractice(userA, prefix + "t03", 10, 202);
        worker.drainOnce();

        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("READY", detail.get("status").asText());
        assertEquals(10, detail.get("items").size());
        assertEquals(0, detail.at("/mixRatio/actual/ai").asInt());
        assertEquals(3, detail.at("/mixRatio/degraded").size());
    }

    // 4. 429 -> 降级 + used_calls 只 +1
    @Test
    void test04_http429DegradesAndQuotaIncrementsOnce() throws Exception {
        seedBankChoices(10);
        stubHandler.set((req, idx) -> new StubResponse(429, "{\"error\":\"rate limit\"}"));
        JsonNode created = createPractice(userA, prefix + "t04", 10, 202);
        worker.drainOnce();

        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("READY", detail.get("status").asText());
        assertEquals(0, detail.at("/mixRatio/actual/ai").asInt());
        Integer used = db.queryForObject("SELECT used_calls FROM rs_ai_quota WHERE scope_key=? AND budget_date=CURRENT_DATE", Integer.class, "user:" + userAId);
        assertEquals(1, used);
    }

    // 5. 超时 -> 降级，error_code=AI_TIMEOUT
    @Test
    void test05_timeoutDegradesWithAiTimeout() throws Exception {
        seedBankChoices(10);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson(threeValidChoiceQuestions(kpId)), 2500));
        JsonNode created = createPractice(userA, prefix + "t05", 10, 202);
        worker.drainOnce();

        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("READY", detail.get("status").asText());
        assertEquals(0, detail.at("/mixRatio/actual/ai").asInt());
        String err = db.queryForObject("SELECT error_code FROM rs_ai_job WHERE user_id=? ORDER BY id DESC LIMIT 1", String.class, userAId);
        assertEquals("AI_TIMEOUT", err);
    }

    // 6. 畸形 JSON -> 不入库，降级
    @Test
    void test06_malformedJsonDegrades() throws Exception {
        seedBankChoices(10);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson("{not valid json")));
        JsonNode created = createPractice(userA, prefix + "t06", 10, 202);
        worker.drainOnce();

        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("READY", detail.get("status").asText());
        assertEquals(0, detail.at("/mixRatio/actual/ai").asInt());
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM rs_question WHERE gen_job_id IN (SELECT id FROM rs_ai_job WHERE user_id=?)", Long.class, userAId));
    }

    // 7. 结构非法（CHOICE 仅 2 选项）-> 不入库，用题库补
    @Test
    void test07_invalidStructureTwoOptionsRejected() throws Exception {
        seedBankChoices(10);
        String twoOptions = """
                {"questions":[{
                  "type":"CHOICE","knowledgePointId":%d,
                  "content":{"stem":"Bad choice","options":[{"key":"A","text":"1"},{"key":"B","text":"2"}]},
                  "answer":{"correctOption":"A"},"rubric":null,"explanation":"e","maxScore":2
                }]}
                """.formatted(kpId);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson(twoOptions)));
        JsonNode created = createPractice(userA, prefix + "t07", 10, 202);
        worker.drainOnce();

        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("READY", detail.get("status").asText());
        assertEquals(10, detail.get("items").size());
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM rs_question WHERE gen_job_id IN (SELECT id FROM rs_ai_job WHERE user_id=?)", Long.class, userAId));
    }

    // 8. 重复题（Jaccard >= 0.85）-> 不入库；重试后仍重复则降级
    @Test
    void test08_duplicateStemRetriedAndDegraded() throws Exception {
        seedBankChoices(10);
        String dupStem = "Bank choice question stem number 0 for testing";
        String dupJson = """
                {"questions":[{
                  "type":"CHOICE","knowledgePointId":%d,
                  "content":{"stem":"%s","options":[{"key":"A","text":"1"},{"key":"B","text":"2"},{"key":"C","text":"3"}]},
                  "answer":{"correctOption":"A"},"rubric":null,"explanation":"e","maxScore":2
                }]}
                """.formatted(kpId, dupStem);
        AtomicInteger calls = new AtomicInteger(0);
        stubHandler.set((req, idx) -> {
            calls.incrementAndGet();
            return new StubResponse(200, chatCompletionJson(dupJson));
        });
        JsonNode created = createPractice(userA, prefix + "t08", 10, 202);
        worker.drainOnce();

        assertEquals(2, calls.get());
        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("READY", detail.get("status").asText());
        assertEquals(0, detail.at("/mixRatio/actual/ai").asInt());
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM rs_question WHERE gen_job_id IN (SELECT id FROM rs_ai_job WHERE user_id=?)", Long.class, userAId));
    }

    // 9. 乙用户抽题（AC-49）
    @Test
    void test09_userBCanDrawAiQuestionGeneratedByUserA() throws Exception {
        seedBankChoices(1);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson(threeValidChoiceQuestions(kpId))));
        createPractice(userA, prefix + "t09a", 4, 202);
        worker.drainOnce();

        JsonNode bPractice = createPractice(userB, prefix + "t09b", 4, 202);
        worker.drainOnce();
        JsonNode bDetail = getPractice(userB, bPractice.get("id").asLong());
        List<Long> drawnQIds = new ArrayList<>();
        bDetail.get("items").forEach(it -> drawnQIds.add(it.get("questionId").asLong()));
        long aiDrawn = drawnQIds.stream().filter(qid -> "AI_GENERATED".equals(
                db.queryForObject("SELECT origin FROM rs_question WHERE id=?", String.class, qid))).count();
        assertTrue(aiDrawn >= 1, "User B should draw at least one AI_GENERATED question");
    }

    // 10. 答案不自洽（correctOption 不在 options）-> 不入库
    @Test
    void test10_inconsistentAnswerRejected() throws Exception {
        seedBankChoices(10);
        String badAnswer = """
                {"questions":[{
                  "type":"CHOICE","knowledgePointId":%d,
                  "content":{"stem":"Unique stem for inconsistent answer test","options":[{"key":"A","text":"1"},{"key":"B","text":"2"},{"key":"C","text":"3"}]},
                  "answer":{"correctOption":"Z"},"rubric":null,"explanation":"e","maxScore":2
                }]}
                """.formatted(kpId);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson(badAnswer)));
        JsonNode created = createPractice(userA, prefix + "t10", 10, 202);
        worker.drainOnce();

        assertEquals(0L, db.queryForObject("SELECT count(*) FROM rs_question WHERE gen_job_id IN (SELECT id FROM rs_ai_job WHERE user_id=?)", Long.class, userAId));
    }

    // 11. 重复 POST /api/practices -> 只一个活动，不产生第二个 job
    @Test
    void test11_idempotentPostPracticeSingleActivityAndJob() throws Exception {
        seedBankChoices(10);
        JsonNode first = createPractice(userA, prefix + "t11", 10, 202);
        JsonNode second = createPractice(userA, prefix + "t11", 10, 202);
        assertEquals(first.get("id").asText(), second.get("id").asText());
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM rs_learning_activity WHERE user_id=?", Long.class, userAId));
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM rs_ai_job WHERE user_id=?", Long.class, userAId));
    }

    // 12. 主观题 4/7 -> normalized=57.14, weight=0.6, evidence.result_y=0.5714
    @Test
    void test12_subjective4of7WritesEvidence() throws Exception {
        seedSubjective(7.0);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson("{\"score\":4,\"missingPoints\":[\"未说明复杂度\"],\"reason\":\"部分正确\"}")));
        JsonNode p = createPractice(userA, prefix + "t12", 1, 201);
        long pid = p.get("id").asLong();
        long itemId = p.at("/items/0/id").asLong();
        startPractice(userA, pid);
        JsonNode res = submitPractice(userA, pid, prefix + "sub12", itemId, Map.of("text", "我的答案"));

        assertEquals(57.14, res.at("/items/0/result/normalized_score").asDouble(), 0.01);
        assertEquals(0.6, res.at("/items/0/result/evidence_weight").asDouble(), 0.01);

        masteryConsumer.poll();
        BigDecimal y = db.queryForObject("SELECT result_y FROM rs_evidence WHERE user_id=? AND submission_id=?", BigDecimal.class, userAId, pid);
        BigDecimal w = db.queryForObject("SELECT weight FROM rs_evidence WHERE user_id=? AND submission_id=?", BigDecimal.class, userAId, pid);
        assertEquals(0, new BigDecimal("0.5714").compareTo(y));
        assertEquals(0, new BigDecimal("0.60").compareTo(w));
    }

    // 13. AC-48 提示 -> 响应含 "不构成正式成绩"
    @Test
    void test13_subjectiveDisclaimerPresent() throws Exception {
        seedSubjective(7.0);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson("{\"score\":5,\"missingPoints\":[],\"reason\":\"不错\"}")));
        JsonNode p = createPractice(userA, prefix + "t13", 1, 201);
        long pid = p.get("id").asLong();
        long itemId = p.at("/items/0/id").asLong();
        startPractice(userA, pid);
        JsonNode res = submitPractice(userA, pid, prefix + "sub13", itemId, Map.of("text", "回答"));
        assertTrue(res.at("/items/0/result/suggestion_disclaimer").asText().contains("不构成正式成绩"));
    }

    // 14. 主观题失败 -> AI_REVIEW_FAILED，rs_evidence 无新增行
    @Test
    void test14_subjectiveFailureWritesNoEvidence() throws Exception {
        seedSubjective(7.0);
        stubHandler.set((req, idx) -> new StubResponse(500, "{\"error\":\"down\"}"));
        JsonNode p = createPractice(userA, prefix + "t14", 1, 201);
        long pid = p.get("id").asLong();
        long itemId = p.at("/items/0/id").asLong();
        startPractice(userA, pid);
        JsonNode res = submitPractice(userA, pid, prefix + "sub14", itemId, Map.of("text", "回答"));
        assertEquals("AI_REVIEW_FAILED", res.at("/items/0/result/grading_status").asText());

        masteryConsumer.poll();
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM rs_evidence WHERE user_id=?", Long.class, userAId));
    }

    // 15. 主观 55% -> 进错题本 PENDING
    @Test
    void test15_subjective55PercentEntersWrongQuestion() throws Exception {
        seedSubjective(10.0);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson("{\"score\":5.5,\"missingPoints\":[\"缺边界\"],\"reason\":\"55%\"}")));
        JsonNode p = createPractice(userA, prefix + "t15", 1, 201);
        long pid = p.get("id").asLong();
        long itemId = p.at("/items/0/id").asLong();
        startPractice(userA, pid);
        submitPractice(userA, pid, prefix + "sub15", itemId, Map.of("text", "回答"));

        String status = db.queryForObject("SELECT status FROM rs_wrong_question WHERE user_id=?", String.class, userAId);
        assertEquals("PENDING", status);
    }

    // 16. judge 超时（AC-52）-> M_final=M_rule，ai_reason_json 标明未生效
    @Test
    void test16_masteryJudgeTimeoutFallsBackToRule() {
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson("{\"score\":80,\"reason\":\"ok\"}"), 2000));
        var evidence = List.of(new MasteryCalculation.Evidence(1L, 1L, BigDecimal.ONE, BigDecimal.ONE, OffsetDateTime.now()));
        var res = MasteryCalculation.calculate(evidence, masteryJudge, userAId, kpId);
        assertEquals(0, res.ruleScore().compareTo(res.finalScore()));
        assertTrue(res.aiReasonJson().contains("\"aiApplied\":false"));
    }

    // 17. judge 返回 120 -> M_final=M_rule
    @Test
    void test17_masteryJudgeOutOfRange120FallsBackToRule() {
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson("{\"score\":120,\"reason\":\"bad\"}")));
        var evidence = List.of(new MasteryCalculation.Evidence(1L, 1L, BigDecimal.ONE, BigDecimal.ONE, OffsetDateTime.now()));
        var res = MasteryCalculation.calculate(evidence, masteryJudge, userAId, kpId);
        assertEquals(0, res.ruleScore().compareTo(res.finalScore()));
        assertNull(res.aiScore());
    }

    // 18. judge 返回 62.5 且 M_rule=51.7 -> M_final=59.7, clamped=true
    @Test
    void test18_masteryJudgeClampedAtPlus8() {
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson("{\"score\":62.5,\"reason\":\"略高于规则分\"}")));
        // Construct evidence giving rule_score = 51.7 (WS=5.2, YS=2.72 -> (2.72+1)/(5.2+2)*100 = 51.666... or 100*(YS+1)/(WS+2)=51.7)
        // With n=1, w=1.0, y=0.551 -> (1.551/3)*100 = 51.700
        var evidence = List.of(new MasteryCalculation.Evidence(1L, 1L, new BigDecimal("0.551"), BigDecimal.ONE, OffsetDateTime.now()));
        var res = MasteryCalculation.calculate(evidence, masteryJudge, userAId, kpId);
        assertEquals(0, new BigDecimal("51.700").compareTo(res.ruleScore().setScale(3)));
        assertEquals(0, new BigDecimal("59.700").compareTo(res.finalScore().setScale(3)));
        assertTrue(res.clamped());
    }

    // 19. AI_ENABLED=false -> 无 AI 题，NoAiAvailabilityProvider 返回 false
    @Test
    void test19_aiDisabledUsesNoAiProvider() {
        new ApplicationContextRunner()
                .withUserConfiguration(AiDisabledTestConfig.class)
                .withPropertyValues("app.ai.enabled=false")
                .run(ctx -> {
                    AiAvailabilityPort port = ctx.getBean(AiAvailabilityPort.class);
                    assertFalse(port.available(1L));
                    assertInstanceOf(NoAiAvailabilityProvider.class, port);
                });
    }

    // 20. 额度耗尽 -> 不建 job，直接降级
    @Test
    void test20_quotaExhaustedDegradesWithoutCreatingJob() throws Exception {
        seedBankChoices(10);
        aiProperties.setDailyCallLimit(0);
        JsonNode created = createPractice(userA, prefix + "t20", 10, 201);
        assertEquals("READY", created.get("status").asText());
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM rs_ai_job WHERE user_id=?", Long.class, userAId));
    }

    // 21. 取消后 worker 完成 -> 不插 item、job=EXPIRED、activity 仍 CANCELLED
    @Test
    void test21_cancelDuringPreparingDiscardsLateWorkerResult() throws Exception {
        seedBankChoices(10);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson(threeValidChoiceQuestions(kpId))));
        JsonNode created = createPractice(userA, prefix + "t21", 10, 202);
        long pid = created.get("id").asLong();
        mvc.perform(post("/api/practices/" + pid + "/cancel").cookie(userA).with(csrf())).andExpect(status().isNoContent());

        worker.drainOnce();
        String actStatus = db.queryForObject("SELECT status FROM rs_learning_activity WHERE id=?", String.class, created.get("activityId").asLong());
        String jobState = db.queryForObject("SELECT state FROM rs_ai_job WHERE user_id=?", String.class, userAId);
        Long itemCount = db.queryForObject("SELECT count(*) FROM rs_practice_item WHERE practice_id=?", Long.class, pid);
        assertEquals("CANCELLED", actStatus);
        assertEquals("EXPIRED", jobState);
        assertEquals(7L, itemCount); // Only the 7 bank items from Phase A; no AI items inserted
    }

    // 22. enabled=true 缺 apiKey -> 启动失败（fail fast）
    @Test
    void test22_enabledWithoutApiKeyFailsFast() {
        new ApplicationContextRunner()
                .withUserConfiguration(AiPropsTestConfig.class)
                .withPropertyValues(
                        "app.ai.enabled=true",
                        "app.ai.base-url=https://api.siliconflow.cn/v1",
                        "app.ai.model=deepseek-ai/DeepSeek-V4.1-Flash",
                        "app.ai.api-key=")
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    // 23. 日志检查 -> 不含 apiKey、不含学生作答全文
    @Test
    void test23_logsDoNotLeakApiKeyOrStudentAnswer() throws Exception {
        seedSubjective(7.0);
        String secretStudentAnswer = "UNIQUE_STUDENT_ANSWER_TEXT_987654321";
        Logger aiLogger = (Logger) LoggerFactory.getLogger("cn.reviewsystem.ai");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        aiLogger.addAppender(appender);
        try {
            stubHandler.set((req, idx) -> new StubResponse(500, "{\"error\":\"fail\"}"));
            JsonNode p = createPractice(userA, prefix + "t23", 1, 201);
            long pid = p.get("id").asLong();
            startPractice(userA, pid);
            submitPractice(userA, pid, prefix + "sub23", p.at("/items/0/id").asLong(), Map.of("text", secretStudentAnswer));
            for (ILoggingEvent ev : appender.list) {
                String msg = ev.getFormattedMessage();
                assertFalse(msg.contains("sk-test-secret-key"), "Log leaked API key");
                assertFalse(msg.contains(secretStudentAnswer), "Log leaked student answer");
            }
        } finally {
            aiLogger.detachAppender(appender);
        }
    }

    // 24. 选项确定性重排
    @Test
    void test24_choiceOptionsShuffledDeterministically() throws Exception {
        ObjectNode baseContent = (ObjectNode) json.readTree("""
                {"stem":"Test stem","options":[
                  {"key":"A","text":"alpha"},{"key":"B","text":"beta"},{"key":"C","text":"gamma"},{"key":"D","text":"delta"}
                ]}""");
        ObjectNode baseAnswer = (ObjectNode) json.readTree("{\"correctOption\":\"B\"}");

        ObjectNode c1 = baseContent.deepCopy();
        ObjectNode a1 = baseAnswer.deepCopy();
        AiOutputValidator.shuffleChoiceOptions(c1, a1, 1001L, 1);

        ObjectNode c2 = baseContent.deepCopy();
        ObjectNode a2 = baseAnswer.deepCopy();
        AiOutputValidator.shuffleChoiceOptions(c2, a2, 1001L, 1);

        assertEquals(c1, c2);
        assertEquals(a1, a2);
        assertEquals("beta", optionTextForKey(c1, a1.get("correctOption").asText()));

        Set<String> distinctCorrectKeys = new HashSet<>();
        for (long qid = 1; qid <= 30; qid++) {
            ObjectNode c = baseContent.deepCopy();
            ObjectNode a = baseAnswer.deepCopy();
            AiOutputValidator.shuffleChoiceOptions(c, a, qid, 1);
            assertEquals("beta", optionTextForKey(c, a.get("correctOption").asText()));
            distinctCorrectKeys.add(a.get("correctOption").asText());
        }
        assertTrue(distinctCorrectKeys.size() > 1, "correctOption should not always stay B");
    }

    // 25. reasoning_content 隔离
    @Test
    void test25_reasoningContentIgnoredAndNeverLoggedOrStored() throws Exception {
        seedBankChoices(10);
        String fakeReasoning = "FAKE_REASONING_JSON_{\"questions\":[{\"bad\":true}]}";
        Logger aiLogger = (Logger) LoggerFactory.getLogger("cn.reviewsystem.ai");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        aiLogger.addAppender(appender);
        try {
            stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionWithReasoning(threeValidChoiceQuestions(kpId), fakeReasoning)));
            JsonNode created = createPractice(userA, prefix + "t25", 10, 202);
            worker.drainOnce();

            JsonNode detail = getPractice(userA, created.get("id").asLong());
            assertEquals("READY", detail.get("status").asText());
            assertEquals(3, detail.at("/mixRatio/actual/ai").asInt());

            String outputJson = db.queryForObject("SELECT output_json::text FROM rs_ai_job WHERE user_id=?", String.class, userAId);
            assertFalse(outputJson.contains("FAKE_REASONING_JSON"));
            for (ILoggingEvent ev : appender.list) {
                assertFalse(ev.getFormattedMessage().contains("FAKE_REASONING_JSON"));
            }
        } finally {
            aiLogger.detachAppender(appender);
        }
    }

    // 26. recoverStaleQueuedExpiredJob
    @Test
    void test26_recoverStaleQueuedExpiredJob() throws Exception {
        seedBankChoices(10);
        JsonNode created = createPractice(userA, prefix + "t26", 10, 202);
        db.update("UPDATE rs_ai_job SET deadline_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE user_id=?", userAId);

        int recovered = jobService.recoverStale(90);
        assertEquals(1, recovered);
        var job = db.queryForMap("SELECT state, error_code FROM rs_ai_job WHERE user_id=?", userAId);
        assertEquals("EXPIRED", job.get("state"));
        assertEquals("AI_TIMEOUT", job.get("error_code"));
        assertEquals("PREPARATION_FAILED", db.queryForObject("SELECT status FROM rs_learning_activity WHERE id=?", String.class, created.get("activityId").asLong()));
    }

    // 27. recoverStaleRunningJobMarksPreparationFailed
    @Test
    void test27_recoverStaleRunningJobMarksPreparationFailed() throws Exception {
        seedBankChoices(10);
        JsonNode created = createPractice(userA, prefix + "t27", 10, 202);
        db.update("UPDATE rs_ai_job SET state='RUNNING', updated_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE user_id=?", userAId);

        int recovered = jobService.recoverStale(90);
        assertEquals(1, recovered);
        var job = db.queryForMap("SELECT state, error_code FROM rs_ai_job WHERE user_id=?", userAId);
        assertEquals("EXPIRED", job.get("state"));
        assertEquals("AI_WORKER_STALE", job.get("error_code"));
        assertEquals("PREPARATION_FAILED", db.queryForObject("SELECT status FROM rs_learning_activity WHERE id=?", String.class, created.get("activityId").asLong()));
    }

    // 28. recoverWritesPreparationFailureReason
    @Test
    void test28_recoverWritesPreparationFailureReason() throws Exception {
        seedBankChoices(10);
        JsonNode created = createPractice(userA, prefix + "t28", 10, 202);
        db.update("UPDATE rs_ai_job SET state='RUNNING', updated_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE user_id=?", userAId);

        jobService.recoverStale(90);
        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("PREPARATION_FAILED", detail.get("status").asText());
        assertEquals("AI_WORKER_STALE", detail.at("/mixRatio/preparationFailure/reason").asText());
    }

    // 29. recoverReleasesActiveActivityUniqueness
    @Test
    void test29_recoverReleasesActiveActivityUniqueness() throws Exception {
        seedBankChoices(10);
        createPractice(userA, prefix + "t29a", 10, 202);
        db.update("UPDATE rs_ai_job SET state='RUNNING', updated_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE user_id=?", userAId);
        jobService.recoverStale(90);

        JsonNode second = createPractice(userA, prefix + "t29b", 10, 202);
        assertEquals("PREPARING", second.get("status").asText());
    }

    // 30. recoverIsIdempotent
    @Test
    void test30_recoverIsIdempotent() throws Exception {
        seedBankChoices(10);
        JsonNode created = createPractice(userA, prefix + "t30", 10, 202);
        db.update("UPDATE rs_ai_job SET state='RUNNING', updated_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE user_id=?", userAId);

        assertEquals(1, jobService.recoverStale(90));
        assertEquals(0, jobService.recoverStale(90));
        assertEquals("PREPARATION_FAILED", db.queryForObject("SELECT status FROM rs_learning_activity WHERE id=?", String.class, created.get("activityId").asLong()));
    }

    // 31. recoverSkipsAlreadyCancelledActivity
    @Test
    void test31_recoverSkipsAlreadyCancelledActivity() throws Exception {
        seedBankChoices(10);
        JsonNode created = createPractice(userA, prefix + "t31", 10, 202);
        long pid = created.get("id").asLong();
        mvc.perform(post("/api/practices/" + pid + "/cancel").cookie(userA).with(csrf())).andExpect(status().isNoContent());
        db.update("UPDATE rs_ai_job SET state='RUNNING', updated_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE user_id=?", userAId);

        assertEquals(1, jobService.recoverStale(90));
        assertEquals("EXPIRED", db.queryForObject("SELECT state FROM rs_ai_job WHERE user_id=?", String.class, userAId));
        assertEquals("CANCELLED", db.queryForObject("SELECT status FROM rs_learning_activity WHERE id=?", String.class, created.get("activityId").asLong()));
        JsonNode detail = getPractice(userA, pid);
        assertTrue(detail.at("/mixRatio/preparationFailure").isMissingNode());
    }

    // 32. workerRuntimeExceptionDoesNotHangJob
    @Test
    void test32_workerRuntimeExceptionDoesNotHangJob() throws Exception {
        seedBankChoices(10);
        JsonNode created = createPractice(userA, prefix + "t32", 10, 202);
        QuestionGenerationService throwingGenerator = new QuestionGenerationService(
                db, json, aiProperties, null, null, null, null, txManager) {
            @Override
            public GenerationOutcome generate(long jobId, long userId, long knowledgeId, Long sourceQuestionVersionId, int requestedCount) {
                throw new RuntimeException("simulated unexpected generator crash");
            }
        };
        AiJobWorker isolatedWorker = new AiJobWorker(db, json, jobService, throwingGenerator, txManager);
        assertEquals(1, isolatedWorker.drainOnce());

        var job = db.queryForMap("SELECT state, error_code FROM rs_ai_job WHERE user_id=?", userAId);
        assertEquals("FAILED", job.get("state"));
        assertEquals("AI_WORKER_ERROR", job.get("error_code"));
        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("PREPARATION_FAILED", detail.get("status").asText());
        assertEquals("AI_WORKER_ERROR", detail.at("/mixRatio/preparationFailure/reason").asText());
    }

    // 33. preparationTimeoutSecondsExposedInView
    @Test
    void test33_preparationTimeoutSecondsExposedInView() throws Exception {
        seedBankChoices(10);
        JsonNode created = createPractice(userA, prefix + "t33", 10, 202);
        assertEquals(aiProperties.getPrepareTimeoutSeconds(), created.get("preparationTimeoutSeconds").asInt());
    }

    // 34. degradedReasonIsAiDisabled
    @Test
    void test34_degradedReasonIsAiDisabled() throws Exception {
        seedBankChoices(10);
        aiProperties.setEnabled(false);
        JsonNode created = createPractice(userA, prefix + "t34", 10, 201);
        assertEquals("AI_DISABLED", created.at("/mixRatio/degraded/0/reason").asText());
    }

    // 35. degradedReasonIsQuotaExceeded
    @Test
    void test35_degradedReasonIsQuotaExceeded() throws Exception {
        seedBankChoices(10);
        aiProperties.setDailyCallLimit(0);
        JsonNode created = createPractice(userA, prefix + "t35", 10, 201);
        assertEquals("AI_QUOTA_EXCEEDED", created.at("/mixRatio/degraded/0/reason").asText());
    }

    // 36. degradedReasonIsRateLimited
    @Test
    void test36_degradedReasonIsRateLimited() throws Exception {
        seedBankChoices(10);
        stubHandler.set((req, idx) -> new StubResponse(429, "{\"error\":\"rate limit\"}"));
        JsonNode created = createPractice(userA, prefix + "t36", 10, 202);
        worker.drainOnce();
        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("AI_RATE_LIMITED", detail.at("/mixRatio/degraded/0/reason").asText());
    }

    // 37. degradedReasonIsServerError
    @Test
    void test37_degradedReasonIsServerError() throws Exception {
        seedBankChoices(10);
        stubHandler.set((req, idx) -> new StubResponse(500, "{\"error\":\"server error\"}"));
        JsonNode created = createPractice(userA, prefix + "t37", 10, 202);
        worker.drainOnce();
        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("AI_SERVER_ERROR", detail.at("/mixRatio/degraded/0/reason").asText());
    }

    // 38. bankShortageRecordedWhenBankInsufficient
    @Test
    void test38_bankShortageRecordedWhenBankInsufficient() throws Exception {
        seedBankChoices(5);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson(fiveValidChoiceQuestions(kpId))));
        JsonNode created = createPractice(userA, prefix + "t38", 10, 202);
        assertEquals(7, created.at("/mixRatio/planned/bank").asInt());
        assertEquals(3, created.at("/mixRatio/planned/ai").asInt());
        assertEquals(5, created.at("/mixRatio/target/bank").asInt());
        assertEquals(5, created.at("/mixRatio/target/ai").asInt());
        assertEquals(2, created.at("/mixRatio/shortage/bankShortfall").asInt());

        worker.drainOnce();
        JsonNode detail = getPractice(userA, created.get("id").asLong());
        assertEquals("READY", detail.get("status").asText());
        assertEquals(5, detail.at("/mixRatio/actual/bank").asInt());
        assertEquals(5, detail.at("/mixRatio/actual/ai").asInt());
        assertEquals(0, detail.at("/mixRatio/degraded").size());
    }

    // 39. noShortageWhenBankSufficient
    @Test
    void test39_noShortageWhenBankSufficient() throws Exception {
        seedBankChoices(10);
        JsonNode created = createPractice(userA, prefix + "t39", 10, 202);
        assertEquals(7, created.at("/mixRatio/target/bank").asInt());
        assertEquals(3, created.at("/mixRatio/target/ai").asInt());
        assertTrue(created.at("/mixRatio/shortage").isMissingNode());
    }

    // 40. extraQuestionsAreTruncatedNotRejected
    @Test
    void test40_extraQuestionsAreTruncatedNotRejected() {
        var batch = outputValidator.validateQuestions(threeValidChoiceQuestions(kpId), 2, kpId, List.of());
        assertEquals(2, batch.accepted().size());
        assertEquals(1, batch.extraDropped());
        assertEquals(0, batch.invalidCount());
        assertTrue(batch.rejectionReasons().contains("EXCEEDED_COUNT_TRUNCATED"));
    }

    // 41. invalidFirstItemBackfilledByLaterItem
    @Test
    void test41_invalidFirstItemBackfilledByLaterItem() {
        String firstInvalidThenTwoValid = """
                {"questions":[
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"Invalid only two options","options":[{"key":"A","text":"1"},{"key":"B","text":"2"}]},"answer":{"correctOption":"A"},"rubric":null,"explanation":"e0","maxScore":2},
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"Valid choice question alpha for backfill test","options":[{"key":"A","text":"a1"},{"key":"B","text":"b1"},{"key":"C","text":"c1"}]},"answer":{"correctOption":"A"},"rubric":null,"explanation":"e1","maxScore":2},
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"Valid choice question beta for backfill test","options":[{"key":"A","text":"a2"},{"key":"B","text":"b2"},{"key":"C","text":"c2"}]},"answer":{"correctOption":"B"},"rubric":null,"explanation":"e2","maxScore":2}
                ]}
                """.formatted(kpId, kpId, kpId);
        var batch = outputValidator.validateQuestions(firstInvalidThenTwoValid, 2, kpId, List.of());
        assertEquals(2, batch.accepted().size());
        assertEquals(0, batch.extraDropped());
        assertEquals(1, batch.invalidCount());
    }

    // 42. reviewTaskFillsToRequestedCountWhenRecommendationShort
    @Test
    void test42_reviewTaskFillsToRequestedCountWhenRecommendationShort() throws Exception {
        // Isolate our test KP so RecommendationService only sees the 5 questions we seed
        List<Long> otherEnabledKps = db.query("SELECT id FROM rs_knowledge_point WHERE enabled=TRUE AND id<>?", (r, i) -> r.getLong(1), kpId);
        if (!otherEnabledKps.isEmpty()) {
            db.update("UPDATE rs_knowledge_point SET enabled=FALSE WHERE id<>?", kpId);
        }
        try {
            seedBankChoices(5);
            stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson(fiveValidChoiceQuestions(kpId))));
            String body = mvc.perform(post("/api/review/tasks").cookie(userA).with(csrf())
                            .header("Idempotency-Key", prefix + "t42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"questionCount\":10}"))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            JsonNode created = json.readTree(body);
            assertEquals("PREPARING", created.get("status").asText());
            assertEquals(10, created.get("questionCount").asInt());
            assertEquals(7, created.at("/mixRatio/planned/bank").asInt());
            assertEquals(3, created.at("/mixRatio/planned/ai").asInt());
            assertEquals(5, created.at("/mixRatio/target/bank").asInt());
            assertEquals(5, created.at("/mixRatio/target/ai").asInt());
            assertEquals(2, created.at("/mixRatio/shortage/bankShortfall").asInt());

            worker.drainOnce();
            JsonNode detail = json.readTree(mvc.perform(get("/api/review/tasks/" + created.get("id").asLong()).cookie(userA))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertEquals("READY", detail.get("status").asText());
            assertEquals(10, detail.get("questionCount").asInt());
            assertEquals(10, detail.get("items").size());
            assertEquals(5, detail.at("/mixRatio/actual/bank").asInt());
            assertEquals(5, detail.at("/mixRatio/actual/ai").asInt());
            assertEquals(2, detail.at("/mixRatio/shortage/bankShortfall").asInt());
            assertEquals(10, detail.at("/mixRatio/shortage/actual").asInt());
        } finally {
            for (long id : otherEnabledKps) {
                db.update("UPDATE rs_knowledge_point SET enabled=TRUE WHERE id=?", id);
            }
        }
    }

    // 43. shortageRefreshedAfterFinalizeWhenPreparing
    @Test
    void test43_shortageRefreshedAfterFinalizeWhenPreparing() throws Exception {
        List<Long> otherEnabledKps = db.query("SELECT id FROM rs_knowledge_point WHERE enabled=TRUE AND id<>?", (r, i) -> r.getLong(1), kpId);
        if (!otherEnabledKps.isEmpty()) {
            db.update("UPDATE rs_knowledge_point SET enabled=FALSE WHERE id<>?", kpId);
        }
        try {
            seedBankChoices(5);
            stubHandler.set((req, idx) -> new StubResponse(500, "{\"error\":\"ai down\"}"));
            String body = mvc.perform(post("/api/review/tasks").cookie(userA).with(csrf())
                            .header("Idempotency-Key", prefix + "t43")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"questionCount\":10}"))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            JsonNode created = json.readTree(body);
            assertEquals(10, created.at("/mixRatio/shortage/actual").asInt());

            worker.drainOnce();
            JsonNode detail = json.readTree(mvc.perform(get("/api/review/tasks/" + created.get("id").asLong()).cookie(userA))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertEquals("READY", detail.get("status").asText());
            assertEquals(5, detail.get("questionCount").asInt());
            assertEquals(10, detail.at("/mixRatio/shortage/requested").asInt());
            assertEquals(5, detail.at("/mixRatio/shortage/actual").asInt());
            assertEquals(2, detail.at("/mixRatio/shortage/bankShortfall").asInt());
            assertEquals("INSUFFICIENT_RECOMMENDATIONS", detail.at("/mixRatio/shortage/reason").asText());
        } finally {
            for (long id : otherEnabledKps) {
                db.update("UPDATE rs_knowledge_point SET enabled=TRUE WHERE id=?", id);
            }
        }
    }

    // 44. shortageWrittenWhenAiDisabledAndBankInsufficient
    @Test
    void test44_shortageWrittenWhenAiDisabledAndBankInsufficient() throws Exception {
        aiProperties.setEnabled(false);
        List<Long> otherEnabledKps = db.query("SELECT id FROM rs_knowledge_point WHERE enabled=TRUE AND id<>?", (r, i) -> r.getLong(1), kpId);
        if (!otherEnabledKps.isEmpty()) {
            db.update("UPDATE rs_knowledge_point SET enabled=FALSE WHERE id<>?", kpId);
        }
        try {
            seedBankChoices(3);
            JsonNode prac = createPractice(userA, prefix + "t44p", 10, 201);
            assertEquals("v3", prac.at("/mixRatio/version").asText());
            assertEquals(10, prac.at("/mixRatio/shortage/requested").asInt());
            assertEquals(3, prac.at("/mixRatio/shortage/actual").asInt());
            assertEquals(4, prac.at("/mixRatio/shortage/bankShortfall").asInt());
            assertEquals("INSUFFICIENT_RECOMMENDATIONS", prac.at("/mixRatio/shortage/reason").asText());
            mvc.perform(post("/api/practices/" + prac.get("id").asLong() + "/cancel").cookie(userA).with(csrf())).andExpect(status().isNoContent());

            JsonNode task = json.readTree(mvc.perform(post("/api/review/tasks").cookie(userA).with(csrf())
                            .header("Idempotency-Key", prefix + "t44t")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"questionCount\":10}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
            assertEquals("v3", task.at("/mixRatio/version").asText());
            assertEquals(10, task.at("/mixRatio/shortage/requested").asInt());
            assertEquals(3, task.at("/mixRatio/shortage/actual").asInt());
            assertEquals(4, task.at("/mixRatio/shortage/bankShortfall").asInt());
            assertEquals("INSUFFICIENT_RECOMMENDATIONS", task.at("/mixRatio/shortage/reason").asText());
        } finally {
            for (long id : otherEnabledKps) {
                db.update("UPDATE rs_knowledge_point SET enabled=TRUE WHERE id=?", id);
            }
        }
    }

    // 45. shortageAbsentWhenRequestFullySatisfied
    @Test
    void test45_shortageAbsentWhenRequestFullySatisfied() throws Exception {
        aiProperties.setEnabled(false);
        seedBankChoices(3);
        JsonNode prac = createPractice(userA, prefix + "t45", 3, 201);
        assertEquals("v3", prac.at("/mixRatio/version").asText());
        assertTrue(prac.at("/mixRatio/shortage").isMissingNode());
    }

    // 46. legacyShortageKeyRemovedEverywhere
    @Test
    void test46_legacyShortageKeyRemovedEverywhere() throws Exception {
        List<Long> otherEnabledKps = db.query("SELECT id FROM rs_knowledge_point WHERE enabled=TRUE AND id<>?", (r, i) -> r.getLong(1), kpId);
        if (!otherEnabledKps.isEmpty()) {
            db.update("UPDATE rs_knowledge_point SET enabled=FALSE WHERE id<>?", kpId);
        }
        try {
            seedBankChoices(3);
            aiProperties.setEnabled(false);
            JsonNode prac = createPractice(userA, prefix + "t46p", 10, 201);
            assertEquals(0, prac.get("mixRatio").findValues("short" + "fall").size());
            mvc.perform(post("/api/practices/" + prac.get("id").asLong() + "/cancel").cookie(userA).with(csrf())).andExpect(status().isNoContent());

            JsonNode taskNoAi = json.readTree(mvc.perform(post("/api/review/tasks").cookie(userA).with(csrf())
                            .header("Idempotency-Key", prefix + "t46t1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"questionCount\":10}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
            assertEquals(0, taskNoAi.get("mixRatio").findValues("short" + "fall").size());
            mvc.perform(post("/api/review/tasks/" + taskNoAi.get("id").asLong() + "/cancel").cookie(userA).with(csrf())).andExpect(status().isNoContent());

            aiProperties.setEnabled(true);
            stubHandler.set((req, idx) -> new StubResponse(500, "{\"error\":\"down\"}"));
            JsonNode taskPrep = json.readTree(mvc.perform(post("/api/review/tasks").cookie(userA).with(csrf())
                            .header("Idempotency-Key", prefix + "t46t2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"questionCount\":10}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
            worker.drainOnce();
            JsonNode taskFinal = json.readTree(mvc.perform(get("/api/review/tasks/" + taskPrep.get("id").asLong()).cookie(userA))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertEquals(0, taskFinal.get("mixRatio").findValues("short" + "fall").size());
        } finally {
            for (long id : otherEnabledKps) {
                db.update("UPDATE rs_knowledge_point SET enabled=TRUE WHERE id=?", id);
            }
        }
    }

    // 47. quotaExhaustedKeepsPending
    @Test
    void test47_quotaExhaustedKeepsPending() throws Exception {
        seedSubjective(10.0);
        db.update("INSERT INTO rs_ai_quota(scope_key,budget_date,used_calls) VALUES (?,CURRENT_DATE,200)", "user:" + userAId);
        JsonNode p = createPractice(userA, prefix + "t47", 1, 201);
        long pid = p.get("id").asLong();
        long itemId = p.at("/items/0/id").asLong();
        startPractice(userA, pid);
        JsonNode res = submitPractice(userA, pid, prefix + "sub47", itemId, Map.of("text", "answer"));
        assertEquals("AI_REVIEW_PENDING", res.at("/items/0/result/grading_status").asText());
        assertEquals("AI_QUOTA_EXCEEDED", res.at("/items/0/result/unavailable_reason").asText());
        assertEquals("AI_REVIEW_PENDING", db.queryForObject("SELECT grading_status FROM rs_practice_item WHERE id=?", String.class, itemId));
    }

    // 48. retryReviewRejectsWhenAiUnavailable
    @Test
    void test48_retryReviewRejectsWhenAiUnavailable() throws Exception {
        List<Long> otherEnabledKps = db.query("SELECT id FROM rs_knowledge_point WHERE enabled=TRUE AND id<>?", (r, i) -> r.getLong(1), kpId);
        if (!otherEnabledKps.isEmpty()) {
            db.update("UPDATE rs_knowledge_point SET enabled=FALSE WHERE id<>?", kpId);
        }
        try {
            seedSubjective(10.0);
            // 1) Practice: fail via 500 -> AI_REVIEW_FAILED, then disable AI and call retry-review -> 409 AI_UNAVAILABLE (AI_DISABLED), status stays AI_REVIEW_FAILED
            stubHandler.set((req, idx) -> new StubResponse(500, "{\"error\":\"down\"}"));
            JsonNode p = createPractice(userA, prefix + "t48p", 1, 201);
            long pid = p.get("id").asLong();
            long pItemId = p.at("/items/0/id").asLong();
            startPractice(userA, pid);
            submitPractice(userA, pid, prefix + "sub48p", pItemId, Map.of("text", "answer"));
            assertEquals("AI_REVIEW_FAILED", db.queryForObject("SELECT grading_status FROM rs_practice_item WHERE id=?", String.class, pItemId));

            aiProperties.setEnabled(false);
            mvc.perform(post("/api/practices/" + pid + "/items/" + pItemId + "/retry-review").cookie(userA).with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("AI_DISABLED"));
            assertEquals("AI_REVIEW_FAILED", db.queryForObject("SELECT grading_status FROM rs_practice_item WHERE id=?", String.class, pItemId));

            // 2) Task: quota exhausted -> AI_REVIEW_PENDING, then call retry-review -> 409 AI_UNAVAILABLE (AI_QUOTA_EXCEEDED), status stays AI_REVIEW_PENDING
            aiProperties.setEnabled(true);
            masteryConsumer.poll();
            db.update("INSERT INTO rs_ai_quota(scope_key,budget_date,used_calls) VALUES (?,CURRENT_DATE,200) ON CONFLICT (scope_key,budget_date) DO UPDATE SET used_calls=200", "user:" + userAId);
            JsonNode task = json.readTree(mvc.perform(post("/api/review/tasks").cookie(userA).with(csrf())
                            .header("Idempotency-Key", prefix + "t48t")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"questionCount\":1}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
            long tid = task.get("id").asLong();
            long tItemId = task.at("/items/0/id").asLong();
            mvc.perform(post("/api/review/tasks/" + tid + "/start").cookie(userA).with(csrf())).andExpect(status().isOk());
            String tSubBody = json.writeValueAsString(Map.of("answers", List.of(Map.of("itemId", String.valueOf(tItemId), "answer", Map.of("text", "ans"), "assetIds", List.of()))));
            mvc.perform(post("/api/review/tasks/" + tid + "/submit").cookie(userA).with(csrf())
                            .header("Idempotency-Key", prefix + "sub48t")
                            .contentType(MediaType.APPLICATION_JSON).content(tSubBody))
                    .andExpect(status().isOk());
            assertEquals("AI_REVIEW_PENDING", db.queryForObject("SELECT grading_status FROM rs_task_item WHERE id=?", String.class, tItemId));

            mvc.perform(post("/api/review/tasks/" + tid + "/items/" + tItemId + "/retry-review").cookie(userA).with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("AI_QUOTA_EXCEEDED"));
            assertEquals("AI_REVIEW_PENDING", db.queryForObject("SELECT grading_status FROM rs_task_item WHERE id=?", String.class, tItemId));
        } finally {
            for (long id : otherEnabledKps) {
                db.update("UPDATE rs_knowledge_point SET enabled=TRUE WHERE id=?", id);
            }
        }
    }

    // 49. retryReviewSucceedsWhenAiRecovers
    @Test
    void test49_retryReviewSucceedsWhenAiRecovers() throws Exception {
        seedSubjective(10.0);
        db.update("INSERT INTO rs_ai_quota(scope_key,budget_date,used_calls) VALUES (?,CURRENT_DATE,200)", "user:" + userAId);
        JsonNode p = createPractice(userA, prefix + "t49", 1, 201);
        long pid = p.get("id").asLong();
        long itemId = p.at("/items/0/id").asLong();
        startPractice(userA, pid);
        submitPractice(userA, pid, prefix + "sub49", itemId, Map.of("text", "answer"));
        masteryConsumer.poll();
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM rs_evidence WHERE user_id=?", Long.class, userAId));

        // Restore quota and retry
        db.update("UPDATE rs_ai_quota SET used_calls=0 WHERE scope_key=?", "user:" + userAId);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson("{\"score\":8,\"missingPoints\":[],\"reason\":\"good\"}")));
        JsonNode retried = json.readTree(mvc.perform(post("/api/practices/" + pid + "/items/" + itemId + "/retry-review").cookie(userA).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("AUTO_GRADED", retried.at("/items/0/result/grading_status").asText());
        masteryConsumer.poll();
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM rs_evidence WHERE user_id=?", Long.class, userAId));
    }

    // 50. failedOutcomeRequiresErrorCode
    @Test
    void test50_failedOutcomeRequiresErrorCode() throws Exception {
        assertThrows(NullPointerException.class, () -> SubjectiveReviewService.ReviewOutcome.failed(null));
        assertThrows(NullPointerException.class, () -> SubjectiveReviewService.ReviewOutcome.unavailable(null));

        seedSubjective(10.0);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson("{\"score\":999,\"missingPoints\":[],\"reason\":\"bad score\"}")));
        JsonNode p = createPractice(userA, prefix + "t50", 1, 201);
        long pid = p.get("id").asLong();
        long itemId = p.at("/items/0/id").asLong();
        startPractice(userA, pid);
        JsonNode res = submitPractice(userA, pid, prefix + "sub50", itemId, Map.of("text", "answer"));
        assertEquals("AI_REVIEW_FAILED", res.at("/items/0/result/grading_status").asText());
        assertEquals("AI_INVALID_REVIEW", res.at("/items/0/result/error_code").asText());
    }

    // 51. recoverStaleDoesNotDeadlock
    @Test
    void test51_recoverStaleDoesNotDeadlock() throws Exception {
        seedBankChoices(5);
        stubHandler.set((req, idx) -> new StubResponse(200, chatCompletionJson(threeValidChoiceQuestions(kpId))));
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        Thread recoverThread = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                try {
                    jobService.recoverStale(1);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }
        });

        Thread workerThread = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                try {
                    long actId = db.queryForObject(
                            "INSERT INTO rs_learning_activity(user_id,subject_id,kind,status) VALUES (?,1,'PRACTICE','PREPARING') ON CONFLICT DO NOTHING RETURNING id",
                            Long.class, userAId);
                    long pracId = db.queryForObject(
                            "INSERT INTO rs_practice(activity_id,user_id,subject_id,question_count,mix_ratio_json,request_key) VALUES (?,?,1,3,'{\"version\":\"v3\",\"planned\":{\"bank\":2,\"ai\":1},\"target\":{\"bank\":2,\"ai\":1},\"actual\":{\"bank\":2,\"ai\":0},\"degraded\":[]}'::jsonb,?) RETURNING id",
                            Long.class, actId, userAId, prefix + "dl-" + i);
                    ObjectNode refs = json.createObjectNode();
                    refs.put("kind", "PRACTICE");
                    refs.put("practiceId", pracId);
                    refs.put("activityId", actId);
                    refs.put("requestedCount", 3);
                    refs.put("gap", 1);
                    refs.putArray("knowledgeIds").add(kpId);
                    refs.putArray("bankQuestionIds");
                    jobService.createJob(
                            userAId,
                            "QUESTION_GENERATION",
                            AiJobService.sha256("dl:" + pracId),
                            "practice:" + pracId,
                            refs,
                            null,
                            kpId,
                            null,
                            "QUEUED",
                            OffsetDateTime.now().minusSeconds(5));
                    worker.drainOnce();
                    db.update("UPDATE rs_learning_activity SET status='CANCELLED' WHERE id=? AND status IN ('PREPARING','READY')", actId);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }
        });

        recoverThread.start();
        workerThread.start();
        recoverThread.join(30000);
        workerThread.join(30000);
        jobService.recoverStale(1);

        boolean hasDeadlock = errors.stream().anyMatch(t -> t.getMessage() != null && t.getMessage().toLowerCase().contains("deadlock"));
        assertFalse(hasDeadlock, "Deadlock detected during concurrent recoverStale and worker drainOnce: " + errors);
        Long inflight = db.queryForObject("SELECT count(*) FROM rs_ai_job WHERE user_id=? AND state IN ('QUEUED','RUNNING')", Long.class, userAId);
        assertEquals(0L, inflight);
    }

    // --- Helpers ---

    @EnableConfigurationProperties(AiProperties.class)
    static class AiPropsTestConfig {}

    @EnableConfigurationProperties(AiProperties.class)
    @org.springframework.context.annotation.Import(NoAiAvailabilityProvider.class)
    static class AiDisabledTestConfig {}

    private long insertUser(String name, BCryptPasswordEncoder encoder) {
        return db.queryForObject("INSERT INTO rs_user(username,password_hash,role) VALUES (?,?,'STUDENT') RETURNING id", Long.class, name, encoder.encode(password));
    }

    private Cookie login(String name) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("RSID");
    }

    private void seedBankChoices(int count) {
        for (int i = 0; i < count; i++) {
            String stem = "Bank choice question stem number " + i + " for testing";
            long q = db.queryForObject("INSERT INTO rs_question(subject_id,question_no,origin,status) VALUES (1,?,'MANUAL','ACTIVE') RETURNING id",
                    Long.class, (prefix + "c" + i).substring(0, Math.min(24, (prefix + "c" + i).length())));
            long v = db.queryForObject("INSERT INTO rs_question_version(question_id,version,subject_id,knowledge_point_id,type,max_score,content_json,answer_json,source_ref) VALUES (?,1,1,?,'CHOICE',2,CAST(? AS jsonb),CAST(? AS jsonb),?) RETURNING id",
                    Long.class, q, kpId,
                    json.valueToTree(Map.of("stem", stem, "options", List.of(Map.of("key", "A", "text", "o1"), Map.of("key", "B", "text", "o2"), Map.of("key", "C", "text", "o3")))).toString(),
                    "{\"correctOption\":\"A\"}", prefix + "c" + i);
            db.update("UPDATE rs_question SET current_version_id=? WHERE id=?", v, q);
        }
    }

    private void seedSubjective(double maxScore) {
        long q = db.queryForObject("INSERT INTO rs_question(subject_id,question_no,origin,status) VALUES (1,?,'MANUAL','ACTIVE') RETURNING id",
                Long.class, (prefix + "subj").substring(0, Math.min(24, (prefix + "subj").length())));
        long v = db.queryForObject("INSERT INTO rs_question_version(question_id,version,subject_id,knowledge_point_id,type,max_score,content_json,answer_json,rubric_json,source_ref) VALUES (?,1,1,?,'SHORT_ANSWER',?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?) RETURNING id",
                Long.class, q, kpId, maxScore,
                json.valueToTree(Map.of("stem", "Explain stack vs queue")).toString(),
                json.valueToTree(Map.of("referenceAnswer", "LIFO vs FIFO")).toString(),
                json.valueToTree(Map.of("items", List.of(Map.of("criterion", "all", "score", maxScore)))).toString(),
                prefix + "subj");
        db.update("UPDATE rs_question SET current_version_id=? WHERE id=?", v, q);
    }

    private JsonNode createPractice(Cookie cookie, String key, int count, int expectedStatus) throws Exception {
        String body = mvc.perform(post("/api/practices").cookie(cookie).with(csrf()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("knowledgeIds", List.of(String.valueOf(kpId)), "questionCount", count))))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode getPractice(Cookie cookie, long id) throws Exception {
        return json.readTree(mvc.perform(get("/api/practices/" + id).cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void startPractice(Cookie cookie, long id) throws Exception {
        mvc.perform(post("/api/practices/" + id + "/start").cookie(cookie).with(csrf())).andExpect(status().isOk());
    }

    private JsonNode submitPractice(Cookie cookie, long id, String key, long itemId, Map<String, Object> answer) throws Exception {
        String body = json.writeValueAsString(Map.of("answers", List.of(Map.of("itemId", String.valueOf(itemId), "answer", answer, "assetIds", List.of()))));
        return json.readTree(mvc.perform(post("/api/practices/" + id + "/submit").cookie(cookie).with(csrf()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String threeValidChoiceQuestions(long kp) {
        return """
                {"questions":[
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"AI generated unique question one about binary tree traversal","options":[{"key":"A","text":"a1"},{"key":"B","text":"b1"},{"key":"C","text":"c1"},{"key":"D","text":"d1"}]},"answer":{"correctOption":"B"},"rubric":null,"explanation":"e1","maxScore":2},
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"AI generated unique question two about hash table collision","options":[{"key":"A","text":"a2"},{"key":"B","text":"b2"},{"key":"C","text":"c2"},{"key":"D","text":"d2"}]},"answer":{"correctOption":"B"},"rubric":null,"explanation":"e2","maxScore":2},
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"AI generated unique question three about quicksort partition","options":[{"key":"A","text":"a3"},{"key":"B","text":"b3"},{"key":"C","text":"c3"},{"key":"D","text":"d3"}]},"answer":{"correctOption":"B"},"rubric":null,"explanation":"e3","maxScore":2}
                ]}
                """.formatted(kp, kp, kp);
    }

    private String fiveValidChoiceQuestions(long kp) {
        return """
                {"questions":[
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"AI generated unique question 1 about binary search tree height","options":[{"key":"A","text":"a1"},{"key":"B","text":"b1"},{"key":"C","text":"c1"},{"key":"D","text":"d1"}]},"answer":{"correctOption":"B"},"rubric":null,"explanation":"e1","maxScore":2},
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"AI generated unique question 2 about hash table open addressing","options":[{"key":"A","text":"a2"},{"key":"B","text":"b2"},{"key":"C","text":"c2"},{"key":"D","text":"d2"}]},"answer":{"correctOption":"B"},"rubric":null,"explanation":"e2","maxScore":2},
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"AI generated unique question 3 about quicksort pivot selection","options":[{"key":"A","text":"a3"},{"key":"B","text":"b3"},{"key":"C","text":"c3"},{"key":"D","text":"d3"}]},"answer":{"correctOption":"B"},"rubric":null,"explanation":"e3","maxScore":2},
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"AI generated unique question 4 about dijkstra shortest path","options":[{"key":"A","text":"a4"},{"key":"B","text":"b4"},{"key":"C","text":"c4"},{"key":"D","text":"d4"}]},"answer":{"correctOption":"B"},"rubric":null,"explanation":"e4","maxScore":2},
                  {"type":"CHOICE","knowledgePointId":%d,"content":{"stem":"AI generated unique question 5 about red black tree rotation","options":[{"key":"A","text":"a5"},{"key":"B","text":"b5"},{"key":"C","text":"c5"},{"key":"D","text":"d5"}]},"answer":{"correctOption":"B"},"rubric":null,"explanation":"e5","maxScore":2}
                ]}
                """.formatted(kp, kp, kp, kp, kp);
    }

    private String chatCompletionJson(String content) throws Exception {
        return chatCompletionWithReasoning(content, "internal reasoning");
    }

    private String chatCompletionWithReasoning(String content, String reasoning) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("id", "stub-1");
        var choices = root.putArray("choices");
        var c0 = choices.addObject();
        var msg = c0.putObject("message");
        msg.put("role", "assistant");
        msg.put("content", content);
        msg.put("reasoning_content", reasoning);
        var usage = root.putObject("usage");
        usage.put("prompt_tokens", 50);
        usage.put("completion_tokens", 120);
        usage.put("total_tokens", 170);
        usage.putObject("completion_tokens_details").put("reasoning_tokens", 40);
        return json.writeValueAsString(root);
    }

    private static String optionTextForKey(JsonNode content, String key) {
        for (JsonNode opt : content.get("options")) {
            if (key.equals(opt.get("key").asText())) {
                return opt.get("text").asText();
            }
        }
        return null;
    }
}
