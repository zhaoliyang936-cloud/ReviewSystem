package cn.reviewsystem.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.reviewsystem.recommend.RecommendationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.ai.scheduler.enabled=false",
        "app.answer-assets.directory=target/test-answer-assets-m10c"
})
@AutoConfigureMockMvc
class M10cReviewCaseTest {

    private static final AtomicInteger CH_SEQ = new AtomicInteger(5000);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired RecommendationService recommendationService;

    final String prefix = "m10c" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    final String password = "valid-password-123";
    long chapterId, kpId, questionId, versionId, adminId;
    Cookie adminCookie;
    final List<Long> userIds = new ArrayList<>();
    final List<Cookie> userCookies = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        var encoder = new BCryptPasswordEncoder();
        adminId = insertUser(prefix + "adm", "ADMIN", encoder);
        adminCookie = login(prefix + "adm");

        for (int i = 0; i < 6; i++) {
            long uid = insertUser(prefix + "u" + i, "STUDENT", encoder);
            userIds.add(uid);
            userCookies.add(login(prefix + "u" + i));
        }

        int n = CH_SEQ.incrementAndGet();
        chapterId = db.queryForObject(
                "INSERT INTO rs_chapter(subject_id,code,name,sort_order) VALUES (1,?,?,?) RETURNING id",
                Long.class, "M10C" + n, prefix + "ch", n);
        kpId = db.queryForObject(
                "INSERT INTO rs_knowledge_point(subject_id,chapter_id,code,name,sort_order) VALUES (1,?,?,?,1) RETURNING id",
                Long.class, chapterId, "M10C" + n + "-KP", prefix + "kp");

        Long seq = db.queryForObject("SELECT nextval('rs_question_no_seq')", Long.class);
        questionId = db.queryForObject(
                "INSERT INTO rs_question(subject_id, question_no, origin, status) VALUES (1, ?, 'MANUAL', 'ACTIVE') RETURNING id",
                Long.class, "DS-" + String.format("%06d", seq));
        versionId = db.queryForObject(
                "INSERT INTO rs_question_version(question_id, version, subject_id, knowledge_point_id, type, max_score, content_json, answer_json, source_ref) "
                        + "VALUES (?, 1, 1, ?, 'CHOICE', 5.0, "
                        + "CAST('{\"stem\":\"M10c复核测试题\",\"options\":[{\"key\":\"A\",\"text\":\"1\"},{\"key\":\"B\",\"text\":\"2\"},{\"key\":\"C\",\"text\":\"3\"}]}' AS jsonb), "
                        + "CAST('{\"correctOption\":\"A\"}' AS jsonb), 'seed') RETURNING id",
                Long.class, questionId, kpId);
        db.update("UPDATE rs_question SET current_version_id=? WHERE id=?", versionId, questionId);

        // Give u0..u5 a mastery snapshot on kpId so RecommendationService includes kpId in REVIEW pool
        for (long uid : userIds) {
            Long snapId = db.queryForObject(
                    "INSERT INTO rs_mastery_snapshot(user_id, knowledge_id, n, weight_sum, weighted_sum, rule_score, ai_score, final_score, clamped, status, ai_reason_json, evidence_revision, rule_version, evidence_ids_json, cutoff_at) "
                            + "VALUES (?, ?, 3, 3.0, 1.5, 50.0, 50.0, 50.0, FALSE, 'CONSOLIDATE', '{}'::jsonb, 1, 'v1', '[]'::jsonb, CURRENT_TIMESTAMP) RETURNING id",
                    Long.class, uid, kpId);
            db.update("INSERT INTO rs_mastery_current(user_id, knowledge_id, snapshot_id, evidence_revision) VALUES (?, ?, ?, 1)",
                    uid, kpId, snapId);
        }
    }

    @AfterEach
    void cleanup() {
        List<Long> allUsers = new ArrayList<>(userIds);
        allUsers.add(adminId);
        for (long uid : allUsers) {
            db.update("DELETE FROM rs_question_feedback WHERE user_id = ?", uid);
            db.update("UPDATE rs_wrong_question SET latest_attempt_id=NULL WHERE user_id = ?", uid);
            db.update("DELETE FROM rs_wrong_attempt WHERE wrong_question_id IN (SELECT id FROM rs_wrong_question WHERE user_id = ?)", uid);
            db.update("UPDATE rs_practice SET redo_wrong_question_id=NULL, variant_wrong_question_id=NULL WHERE user_id = ?", uid);
            db.update("DELETE FROM rs_wrong_question WHERE user_id = ?", uid);
            db.update("DELETE FROM rs_practice_item WHERE practice_id IN (SELECT id FROM rs_practice WHERE user_id = ?)", uid);
            db.update("DELETE FROM rs_practice WHERE user_id = ?", uid);
            db.update("DELETE FROM rs_learning_activity WHERE user_id = ?", uid);
            db.update("DELETE FROM rs_student_course_guard WHERE user_id = ?", uid);
            db.update("DELETE FROM rs_mastery_current WHERE user_id = ?", uid);
            db.update("DELETE FROM rs_mastery_snapshot WHERE user_id = ?", uid);
            db.update("DELETE FROM rs_ai_job WHERE user_id = ?", uid);
        }
        db.update("DELETE FROM rs_question_review_case WHERE question_id = ?", questionId);
        db.update("UPDATE rs_question SET current_version_id=NULL WHERE id = ?", questionId);
        db.update("DELETE FROM rs_question_version WHERE question_id = ?", questionId);
        db.update("DELETE FROM rs_question WHERE id = ?", questionId);
        db.update("DELETE FROM rs_knowledge_point WHERE id = ?", kpId);
        db.update("DELETE FROM rs_chapter WHERE id = ?", chapterId);
        for (long uid : allUsers) {
            db.update("DELETE FROM rs_session WHERE user_id = ?", uid);
            db.update("DELETE FROM rs_user WHERE id = ?", uid);
        }
    }

    private long insertUser(String username, String role, BCryptPasswordEncoder encoder) {
        return Objects.requireNonNull(db.queryForObject(
                "INSERT INTO rs_user(username, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class, username, encoder.encode(password), role));
    }

    private Cookie login(String username) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return Objects.requireNonNull(res.getResponse().getCookie("RSID"));
    }

    private long createWrongQuestionFor(long uid) {
        Long wqId = db.queryForObject(
                "INSERT INTO rs_wrong_question(user_id, question_id, question_version_id, knowledge_id, source_type, status, wrong_count) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', 'PENDING', 1) RETURNING id",
                Long.class, uid, questionId, versionId, kpId);
        return Objects.requireNonNull(wqId);
    }

    private void submitFeedback(int userIdx, String comment) throws Exception {
        long wqId = createWrongQuestionFor(userIds.get(userIdx));
        mvc.perform(post("/api/wrong-questions/" + wqId + "/feedback")
                        .with(csrf())
                        .cookie(userCookies.get(userIdx))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SUSPECTED_ERROR\",\"comment\":\"" + comment + "\"}"))
                .andExpect(status().isOk());
    }

    private void ensureFeedbackAfter(int userIdx, Instant afterResolvedAt) {
        long uid = userIds.get(userIdx);
        db.update("UPDATE rs_question_feedback SET created_at = ? WHERE user_id = ? AND question_id = ?",
                Timestamp.from(afterResolvedAt), uid, questionId);
    }

    @Test
    void t1_threeReportsCreateOpenCaseAndExcludeFromRecommendationAndPractice() throws Exception {
        for (int i = 0; i < 3; i++) {
            submitFeedback(i, "T1举报-" + i);
        }

        Long openCount = db.queryForObject(
                "SELECT count(*) FROM rs_question_review_case WHERE question_id = ? AND status = 'OPEN'",
                Long.class, questionId);
        Integer triggerCount = db.queryForObject(
                "SELECT trigger_feedback_count FROM rs_question_review_case WHERE question_id = ? AND status = 'OPEN'",
                Integer.class, questionId);
        assertEquals(1L, openCount);
        assertEquals(3, triggerCount);

        // Excluded from RecommendationService
        assertTrue(recommendationService.recommend(userIds.get(0), 20).items().stream()
                .noneMatch(it -> Long.parseLong(it.questionId()) == questionId));

        // Excluded from PracticeService candidates (kpId only has this 1 question -> NO_AVAILABLE_QUESTION)
        mvc.perform(post("/api/practices")
                        .with(csrf())
                        .cookie(userCookies.get(0))
                        .header("Idempotency-Key", prefix + "-t1-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeIds\":[\"" + kpId + "\"],\"questionCount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_AVAILABLE_QUESTION"));
    }

    @Test
    void t2_adminResolveRestoresQuestionToRecommendationAndPracticeCandidates() throws Exception {
        for (int i = 0; i < 3; i++) {
            submitFeedback(i, "T2举报-" + i);
        }
        long caseId = Objects.requireNonNull(db.queryForObject(
                "SELECT id FROM rs_question_review_case WHERE question_id = ? AND status = 'OPEN'",
                Long.class, questionId));

        mvc.perform(post("/api/admin/review-cases/" + caseId + "/resolve")
                        .with(csrf())
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        String dbStatus = db.queryForObject(
                "SELECT status FROM rs_question_review_case WHERE id = ?", String.class, caseId);
        Timestamp resolvedAt = db.queryForObject(
                "SELECT resolved_at FROM rs_question_review_case WHERE id = ?", Timestamp.class, caseId);
        assertEquals("RESOLVED", dbStatus);
        assertNotNull(resolvedAt);

        // Restored in RecommendationService
        assertTrue(recommendationService.recommend(userIds.get(0), 20).items().stream()
                .anyMatch(it -> Long.parseLong(it.questionId()) == questionId));

        // Restored in PracticeService candidates
        mvc.perform(post("/api/practices")
                        .with(csrf())
                        .cookie(userCookies.get(0))
                        .header("Idempotency-Key", prefix + "-t2-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeIds\":[\"" + kpId + "\"],\"questionCount\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].questionId").value(String.valueOf(questionId)));
    }

    @Test
    void t3_oneNewReportAfterResolveDoesNotReopenCase() throws Exception {
        for (int i = 0; i < 3; i++) {
            submitFeedback(i, "T3初报-" + i);
        }
        long caseId = Objects.requireNonNull(db.queryForObject(
                "SELECT id FROM rs_question_review_case WHERE question_id = ? AND status = 'OPEN'",
                Long.class, questionId));
        mvc.perform(post("/api/admin/review-cases/" + caseId + "/resolve")
                        .with(csrf())
                        .cookie(adminCookie))
                .andExpect(status().isOk());

        Instant resolvedAt = Objects.requireNonNull(db.queryForObject(
                "SELECT resolved_at FROM rs_question_review_case WHERE id = ?",
                Timestamp.class, caseId)).toInstant();

        // 1 new student reports AFTER resolve
        submitFeedback(3, "T3复报-1");
        ensureFeedbackAfter(3, resolvedAt.plusSeconds(5));

        // Re-check count: total cases must still be 1 (no OPEN case created)
        Long totalCases = db.queryForObject(
                "SELECT count(*) FROM rs_question_review_case WHERE question_id = ?",
                Long.class, questionId);
        Long openCases = db.queryForObject(
                "SELECT count(*) FROM rs_question_review_case WHERE question_id = ? AND status = 'OPEN'",
                Long.class, questionId);
        assertEquals(1L, totalCases);
        assertEquals(0L, openCases);
    }

    @Test
    void t4_threeNewReportsAfterResolveReopenNewCaseWhileKeepingOldResolved() throws Exception {
        for (int i = 0; i < 3; i++) {
            submitFeedback(i, "T4初报-" + i);
        }
        long oldCaseId = Objects.requireNonNull(db.queryForObject(
                "SELECT id FROM rs_question_review_case WHERE question_id = ? AND status = 'OPEN'",
                Long.class, questionId));
        mvc.perform(post("/api/admin/review-cases/" + oldCaseId + "/resolve")
                        .with(csrf())
                        .cookie(adminCookie))
                .andExpect(status().isOk());

        // Set resolved_at slightly in the past so subsequent feedback created_at is strictly > resolved_at
        db.update("UPDATE rs_question_review_case SET resolved_at = CURRENT_TIMESTAMP - INTERVAL '10 seconds' WHERE id = ?", oldCaseId);

        // 3 new students (u3, u4, u5) report after resolve
        for (int i = 3; i < 6; i++) {
            submitFeedback(i, "T4复报-" + i);
        }

        Long totalCases = db.queryForObject(
                "SELECT count(*) FROM rs_question_review_case WHERE question_id = ?",
                Long.class, questionId);
        Long openCases = db.queryForObject(
                "SELECT count(*) FROM rs_question_review_case WHERE question_id = ? AND status = 'OPEN'",
                Long.class, questionId);
        Long newCaseId = db.queryForObject(
                "SELECT id FROM rs_question_review_case WHERE question_id = ? AND status = 'OPEN'",
                Long.class, questionId);
        String oldStatus = db.queryForObject(
                "SELECT status FROM rs_question_review_case WHERE id = ?",
                String.class, oldCaseId);

        assertEquals(2L, totalCases);
        assertEquals(1L, openCases);
        assertNotNull(newCaseId);
        assertNotEquals(oldCaseId, newCaseId);
        assertEquals("RESOLVED", oldStatus);

        // Filtered again in RecommendationService and PracticeService
        assertTrue(recommendationService.recommend(userIds.get(0), 20).items().stream()
                .noneMatch(it -> Long.parseLong(it.questionId()) == questionId));
        mvc.perform(post("/api/practices")
                        .with(csrf())
                        .cookie(userCookies.get(0))
                        .header("Idempotency-Key", prefix + "-t4-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeIds\":[\"" + kpId + "\"],\"questionCount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_AVAILABLE_QUESTION"));
    }

    @Test
    void t5_fiveStudentsConcurrentFeedbackCreatesSingleOpenCaseWithoutErrors() throws Exception {
        List<Long> wqIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            wqIds.add(createWrongQuestionFor(userIds.get(i)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/wrong-questions/" + wqIds.get(idx) + "/feedback")
                                .with(csrf())
                                .cookie(userCookies.get(idx))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"category\":\"SUSPECTED_ERROR\",\"comment\":\"并发举报-" + idx + "\"}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> f : futures) {
            assertEquals(200, f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();

        Long openCount = db.queryForObject(
                "SELECT count(*) FROM rs_question_review_case WHERE question_id = ? AND status = 'OPEN'",
                Long.class, questionId);
        assertEquals(1L, openCount);
    }
}
