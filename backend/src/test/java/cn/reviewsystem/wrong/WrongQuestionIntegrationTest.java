package cn.reviewsystem.wrong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.reviewsystem.practice.PracticeDtos;
import cn.reviewsystem.practice.PracticeService;
import cn.reviewsystem.review.ReviewTaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WrongQuestionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired PracticeService practices;
    @Autowired ReviewTaskService reviewTasks;
    @Autowired WrongQuestionRecorder recorder;

    private final String prefix = "m09" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final String password = "valid-password-123";
    private long userId, otherUserId, chapterId, kpId, kp2Id;
    private Cookie userCookie, otherCookie;

    @BeforeEach
    void setup() throws Exception {
        var encoder = new BCryptPasswordEncoder();
        userId = createUser(prefix + "u1", encoder);
        otherUserId = createUser(prefix + "u2", encoder);
        userCookie = login(prefix + "u1");
        otherCookie = login(prefix + "u2");
        int n = 300 + Math.floorMod(prefix.hashCode(), 600);
        chapterId = db.queryForObject(
                "INSERT INTO rs_chapter(subject_id, code, name, sort_order) VALUES (1, ?, ?, ?) RETURNING id",
                Long.class, "M09-" + n, prefix + "-chapter", n
        );
        kpId = createKnowledge(chapterId, "M09-" + n + "-KP1", prefix + "-kp1", 1);
        kp2Id = createKnowledge(chapterId, "M09-" + n + "-KP2", prefix + "-kp2", 2);
    }

    @Test
    void objectiveWrongCreatesEntryAndRepeatedWrongUpdatesSameEntry() throws Exception {
        long qId = createChoiceQuestion(kpId, "q-repeat", "MANUAL", "A");

        // First wrong via practice
        PracticeDtos.PracticeView p1 = practices.create(userId, prefix + "-p1",
                new PracticeDtos.CreateRequest(List.of(String.valueOf(kpId)), 1, null, null, null));
        practices.start(userId, Long.parseLong(p1.id()), null);
        practices.submit(userId, Long.parseLong(p1.id()), prefix + "-s1",
                new PracticeDtos.SubmitRequest(List.of(new PracticeDtos.FinalAnswer(
                        p1.items().getFirst().id(), json.valueToTree(Map.of("selectedOption", "B")), List.of()
                )), null, null, null));

        Long count = db.queryForObject("SELECT count(*) FROM rs_wrong_question WHERE user_id = ? AND question_id = ?",
                Long.class, userId, qId);
        assertEquals(1L, count);
        var wq = db.queryForMap("SELECT id, status, wrong_count, first_wrong_at, last_wrong_at FROM rs_wrong_question WHERE user_id = ? AND question_id = ?",
                userId, qId);
        assertEquals("PENDING", wq.get("status"));
        assertEquals(1, ((Number) wq.get("wrong_count")).intValue());
        long wqId = ((Number) wq.get("id")).longValue();

        OffsetDateTime firstWrongAt = db.queryForObject("SELECT first_wrong_at FROM rs_wrong_question WHERE id = ?",
                OffsetDateTime.class, wqId);

        // Backdate last_wrong_at slightly so second wrong clearly advances it
        db.update("UPDATE rs_wrong_question SET last_wrong_at = last_wrong_at - INTERVAL '5 minutes' WHERE id = ?", wqId);
        OffsetDateTime prevLastWrong = db.queryForObject("SELECT last_wrong_at FROM rs_wrong_question WHERE id = ?",
                OffsetDateTime.class, wqId);

        // Second wrong on same question
        PracticeDtos.PracticeView p2 = practices.create(userId, prefix + "-p2",
                new PracticeDtos.CreateRequest(List.of(String.valueOf(kpId)), 1, null, null, null));
        practices.start(userId, Long.parseLong(p2.id()), null);
        practices.submit(userId, Long.parseLong(p2.id()), prefix + "-s2",
                new PracticeDtos.SubmitRequest(List.of(new PracticeDtos.FinalAnswer(
                        p2.items().getFirst().id(), json.valueToTree(Map.of("selectedOption", "C")), List.of()
                )), null, null, null));

        assertEquals(1L, db.queryForObject("SELECT count(*) FROM rs_wrong_question WHERE user_id = ? AND question_id = ?",
                Long.class, userId, qId));
        var wqAfter = db.queryForMap("SELECT wrong_count, last_wrong_at, first_wrong_at FROM rs_wrong_question WHERE id = ?", wqId);
        assertEquals(2, ((Number) wqAfter.get("wrong_count")).intValue());
        assertEquals(firstWrongAt, db.queryForObject("SELECT first_wrong_at FROM rs_wrong_question WHERE id = ?",
                OffsetDateTime.class, wqId));
        OffsetDateTime newLastWrong = db.queryForObject("SELECT last_wrong_at FROM rs_wrong_question WHERE id = ?",
                OffsetDateTime.class, wqId);
        assertTrue(newLastWrong.isAfter(prevLastWrong));
        assertEquals(2L, db.queryForObject("SELECT count(*) FROM rs_wrong_attempt WHERE wrong_question_id = ?",
                Long.class, wqId));
    }

    @Test
    void subjectiveAiPendingDoesNotCreateWrongQuestion() {
        long qId = createSubjectiveQuestion(kpId, "q-subj-pending", "SHORT_ANSWER");

        PracticeDtos.PracticeView p = practices.create(userId, prefix + "-subj",
                new PracticeDtos.CreateRequest(List.of(String.valueOf(kpId)), 1, null, null, null));
        practices.start(userId, Long.parseLong(p.id()), null);
        practices.submit(userId, Long.parseLong(p.id()), prefix + "-subj-s",
                new PracticeDtos.SubmitRequest(List.of(new PracticeDtos.FinalAnswer(
                        p.items().getFirst().id(), json.valueToTree(Map.of("text", "my answer")), List.of()
                )), null, null, null));

        assertEquals(0L, db.queryForObject("SELECT count(*) FROM rs_wrong_question WHERE user_id = ? AND question_id = ?",
                Long.class, userId, qId));
    }

    @Test
    void redoPassMarksMasteredAndKeepsAttemptHistory() throws Exception {
        long qId = createChoiceQuestion(kpId, "q-redo-pass", "MANUAL", "A");
        long wqId = failQuestionInPractice(qId, "fail-1");

        MvcResult redoRes = mvc.perform(post("/api/wrong-questions/" + wqId + "/redo")
                        .cookie(userCookie).with(csrf())
                        .header("Idempotency-Key", prefix + "-redo-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"ORIGINAL\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode redoPractice = json.readTree(redoRes.getResponse().getContentAsString());
        long redoPracticeId = redoPractice.get("id").asLong();
        String itemId = redoPractice.at("/items/0/id").asText();

        practices.start(userId, redoPracticeId, null);
        practices.submit(userId, redoPracticeId, prefix + "-redo-submit",
                new PracticeDtos.SubmitRequest(List.of(new PracticeDtos.FinalAnswer(
                        itemId, json.valueToTree(Map.of("selectedOption", "A")), List.of()
                )), null, null, null));

        var wq = db.queryForMap("SELECT status, mastered_at FROM rs_wrong_question WHERE id = ?", wqId);
        assertEquals("MASTERED", wq.get("status"));
        assertNotNull(wq.get("mastered_at"));
        assertEquals(2L, db.queryForObject("SELECT count(*) FROM rs_wrong_attempt WHERE wrong_question_id = ?",
                Long.class, wqId));
    }

    @Test
    void masteredQuestionFailedAgainRestoresPendingAndIncrementsCount() throws Exception {
        long qId = createChoiceQuestion(kpId, "q-reopen", "MANUAL", "A");
        long wqId = failQuestionInPractice(qId, "fail-initial");

        // Redo and pass
        PracticeDtos.PracticeView redo = practices.createForQuestion(userId, wqId, prefix + "-redo-reopen");
        practices.start(userId, Long.parseLong(redo.id()), null);
        practices.submit(userId, Long.parseLong(redo.id()), prefix + "-redo-reopen-sub",
                new PracticeDtos.SubmitRequest(List.of(new PracticeDtos.FinalAnswer(
                        redo.items().getFirst().id(), json.valueToTree(Map.of("selectedOption", "A")), List.of()
                )), null, null, null));
        assertEquals("MASTERED", db.queryForObject("SELECT status FROM rs_wrong_question WHERE id = ?",
                String.class, wqId));

        // Fail again in another practice
        failQuestionInPractice(qId, "fail-again");

        var wq = db.queryForMap("SELECT status, wrong_count, mastered_at FROM rs_wrong_question WHERE id = ?", wqId);
        assertEquals("PENDING", wq.get("status"));
        assertEquals(2, ((Number) wq.get("wrong_count")).intValue());
        assertNull(wq.get("mastered_at"));
        assertEquals(3L, db.queryForObject("SELECT count(*) FROM rs_wrong_attempt WHERE wrong_question_id = ?",
                Long.class, wqId));
    }

    @Test
    void ordinaryPracticePassDoesNotMarkWrongQuestionMastered() {
        long qId = createChoiceQuestion(kpId, "q-ordinary-pass", "MANUAL", "A");
        long wqId = failQuestionInPractice(qId, "fail-ord");

        // Ordinary practice picking the same question and answering correctly
        PracticeDtos.PracticeView ord = practices.create(userId, prefix + "-ord-pass",
                new PracticeDtos.CreateRequest(List.of(String.valueOf(kpId)), 1, null, null, null));
        practices.start(userId, Long.parseLong(ord.id()), null);
        practices.submit(userId, Long.parseLong(ord.id()), prefix + "-ord-pass-sub",
                new PracticeDtos.SubmitRequest(List.of(new PracticeDtos.FinalAnswer(
                        ord.items().getFirst().id(), json.valueToTree(Map.of("selectedOption", "A")), List.of()
                )), null, null, null));

        var wq = db.queryForMap("SELECT status, mastered_at FROM rs_wrong_question WHERE id = ?", wqId);
        assertEquals("PENDING", wq.get("status"));
        assertNull(wq.get("mastered_at"));
        assertEquals(2L, db.queryForObject("SELECT count(*) FROM rs_wrong_attempt WHERE wrong_question_id = ?",
                Long.class, wqId));
    }

    @Test
    void otherUserWrongQuestionDetailAndRedoReturn404() throws Exception {
        long qId = createChoiceQuestion(kpId, "q-owner", "MANUAL", "A");
        long wqId = failQuestionInPractice(qId, "fail-owner");

        mvc.perform(get("/api/wrong-questions/" + wqId).cookie(otherCookie))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/wrong-questions/" + wqId + "/redo")
                        .cookie(otherCookie).with(csrf())
                        .header("Idempotency-Key", prefix + "-other-redo"))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeActivityBlocksRedoWith409AndIdempotencyKeyReplaysSamePractice() throws Exception {
        long q1 = createChoiceQuestion(kpId, "q-active-1", "MANUAL", "A");
        long wqId = failQuestionInPractice(q1, "fail-act");
        long q2 = createChoiceQuestion(kp2Id, "q-active-2", "MANUAL", "A");

        // Idempotent redo creation
        MvcResult firstRedo = mvc.perform(post("/api/wrong-questions/" + wqId + "/redo")
                        .cookie(userCookie).with(csrf())
                        .header("Idempotency-Key", prefix + "-idem-redo"))
                .andExpect(status().isCreated())
                .andReturn();
        String redoPracticeId = json.readTree(firstRedo.getResponse().getContentAsString()).get("id").asText();

        // Replay same idempotency key returns same practiceId even though activity is active
        MvcResult replayRedo = mvc.perform(post("/api/wrong-questions/" + wqId + "/redo")
                        .cookie(userCookie).with(csrf())
                        .header("Idempotency-Key", prefix + "-idem-redo"))
                .andExpect(status().isCreated())
                .andReturn();
        assertEquals(redoPracticeId, json.readTree(replayRedo.getResponse().getContentAsString()).get("id").asText());

        // Different idempotency key while activity is active -> 409 STATE_CONFLICT
        mvc.perform(post("/api/wrong-questions/" + wqId + "/redo")
                        .cookie(userCookie).with(csrf())
                        .header("Idempotency-Key", prefix + "-new-redo-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        // Cancel active practice, start another normal practice on kp2, verify redo still conflicts
        practices.cancel(userId, Long.parseLong(redoPracticeId), null);
        practices.create(userId, prefix + "-block-p",
                new PracticeDtos.CreateRequest(List.of(String.valueOf(kp2Id)), 1, null, null, null));
        assertNotEquals(q1, q2);
        mvc.perform(post("/api/wrong-questions/" + wqId + "/redo")
                        .cookie(userCookie).with(csrf())
                        .header("Idempotency-Key", prefix + "-blocked-by-other"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void suspendedQuestionRedoReturns409() throws Exception {
        long qId = createChoiceQuestion(kpId, "q-susp", "MANUAL", "A");
        long wqId = failQuestionInPractice(qId, "fail-susp");

        db.update("UPDATE rs_question SET status = 'SUSPENDED' WHERE id = ?", qId);

        mvc.perform(post("/api/wrong-questions/" + wqId + "/redo")
                        .cookie(userCookie).with(csrf())
                        .header("Idempotency-Key", prefix + "-redo-susp"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUESTION_SUSPENDED"));
    }

    @Test
    void listDefaultsToPendingAndSupportsMasteredAndAttributeFilters() throws Exception {
        long qPending = createChoiceQuestion(kpId, "q-list-1", "MANUAL", "A");
        long qMastered = createChoiceQuestion(kp2Id, "q-list-2", "AI_GENERATED", "A");
        long wqPending = failQuestionInPractice(qPending, "fail-l1");
        long wqMastered = failQuestionInPractice(qMastered, "fail-l2");

        // Master wqMastered via redo
        PracticeDtos.PracticeView redo = practices.createForQuestion(userId, wqMastered, prefix + "-redo-l2");
        practices.start(userId, Long.parseLong(redo.id()), null);
        practices.submit(userId, Long.parseLong(redo.id()), prefix + "-redo-l2-sub",
                new PracticeDtos.SubmitRequest(List.of(new PracticeDtos.FinalAnswer(
                        redo.items().getFirst().id(), json.valueToTree(Map.of("selectedOption", "A")), List.of()
                )), null, null, null));

        // Default list returns only PENDING
        mvc.perform(get("/api/wrong-questions").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(String.valueOf(wqPending)))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));

        // Explicit status=MASTERED returns mastered item
        mvc.perform(get("/api/wrong-questions").param("status", "MASTERED").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(String.valueOf(wqMastered)))
                .andExpect(jsonPath("$.items[0].sourceType").value("AI_GENERATED"));

        // Filter by source=MANUAL and status=ALL
        mvc.perform(get("/api/wrong-questions").param("status", "ALL").param("source", "MANUAL").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(String.valueOf(wqPending)));

        // Subjective low score with LLM_SUGGESTED marks aiJudged=true
        long qSubj = createSubjectiveQuestion(kpId, "q-subj-low", "SHORT_ANSWER");
        PracticeDtos.PracticeView pSubj = practices.create(userId, prefix + "-p-subj-low",
                new PracticeDtos.CreateRequest(List.of(String.valueOf(kpId)), 1, null, null, null));
        long subjItemId = Long.parseLong(pSubj.items().getFirst().id());
        long subjVerId = Long.parseLong(pSubj.items().getFirst().questionVersionId());
        ObjectNode llmGrade = json.createObjectNode();
        llmGrade.put("grading_mode", "LLM_SUGGESTED");
        llmGrade.put("normalized_score", 55);
        llmGrade.put("passed", false);
        recorder.record(userId, WrongQuestionRecorder.Source.PRACTICE, Long.parseLong(pSubj.id()), subjItemId, null,
                qSubj, subjVerId, kpId, "SHORT_ANSWER", false, json.valueToTree(Map.of("text", "weak")), llmGrade,
                "ORDINARY", null);
        practices.cancel(userId, Long.parseLong(pSubj.id()), null);

        mvc.perform(get("/api/wrong-questions").param("type", "SHORT_ANSWER").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].aiJudged").value(true));
    }

    @Test
    void feedbackIsIdempotentAndDoesNotChangeStatusAndTaskSubmitRecordsWrong() throws Exception {
        long qId = createChoiceQuestion(kpId, "q-fb", "MANUAL", "A");

        // Record via ReviewTaskService.submit
        ReviewTaskService.View task = reviewTasks.create(userId, prefix + "-task", new ReviewTaskService.CreateRequest(1));
        long taskId = Long.parseLong(task.id());
        reviewTasks.start(userId, taskId);
        reviewTasks.submit(userId, taskId, prefix + "-task-sub",
                new ReviewTaskService.SubmitRequest(List.of(new ReviewTaskService.FinalAnswer(
                        task.items().getFirst().id(), json.valueToTree(Map.of("selectedOption", "B")), List.of()
                )), null, null, null));

        Long wqId = db.queryForObject("SELECT id FROM rs_wrong_question WHERE user_id = ? AND question_id = ?",
                Long.class, userId, qId);
        assertNotNull(wqId);

        String fbBody = json.writeValueAsString(Map.of("category", "SUSPECTED_ERROR", "comment", "Option A looks wrong"));
        MvcResult fb1 = mvc.perform(post("/api/wrong-questions/" + wqId + "/feedback")
                        .cookie(userCookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fbBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("SUSPECTED_ERROR"))
                .andReturn();
        String fbId1 = json.readTree(fb1.getResponse().getContentAsString()).get("id").asText();

        // Duplicate feedback submission returns same row and does not create a second row
        MvcResult fb2 = mvc.perform(post("/api/wrong-questions/" + wqId + "/feedback")
                        .cookie(userCookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fbBody))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(fbId1, json.readTree(fb2.getResponse().getContentAsString()).get("id").asText());
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM rs_question_feedback WHERE wrong_question_id = ? AND user_id = ?",
                Long.class, wqId, userId));
        assertEquals("PENDING", db.queryForObject("SELECT status FROM rs_wrong_question WHERE id = ?",
                String.class, wqId));

        // Variant endpoint returns 409 AI_UNAVAILABLE when AI is disabled
        mvc.perform(post("/api/wrong-questions/" + wqId + "/variant")
                        .cookie(userCookie).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
    }

    @Test
    void versionUpgradeUpdatesWrongQuestionVersionAndRedoUsesCurrentVersion() throws Exception {
        long qId = createChoiceQuestion(kpId, "q-ver", "MANUAL", "A");
        long v1Id = db.queryForObject("SELECT current_version_id FROM rs_question WHERE id = ?", Long.class, qId);

        // 1. Fail on v1
        long wqId = failQuestionInPractice(qId, "fail-v1");
        assertEquals(v1Id, db.queryForObject("SELECT question_version_id FROM rs_wrong_question WHERE id = ?", Long.class, wqId));
        long firstAttemptId = db.queryForObject("SELECT latest_attempt_id FROM rs_wrong_question WHERE id = ?", Long.class, wqId);

        // 2. Upgrade question to v2
        String contentV2 = json.writeValueAsString(Map.of(
                "stem", "Stem for q-ver v2",
                "options", List.of(
                        Map.of("key", "A", "text", "Opt A v2"),
                        Map.of("key", "B", "text", "Opt B v2"),
                        Map.of("key", "C", "text", "Opt C v2"),
                        Map.of("key", "D", "text", "Opt D v2")
                ),
                "explanation", "Exp v2"
        ));
        String answerV2 = json.writeValueAsString(Map.of("correctOption", "B"));
        long v2Id = db.queryForObject(
                "INSERT INTO rs_question_version(question_id, version, subject_id, knowledge_point_id, type, content_json, answer_json, max_score) VALUES (?, 2, 1, ?, 'CHOICE', CAST(? AS jsonb), CAST(? AS jsonb), 2) RETURNING id",
                Long.class, qId, kp2Id, contentV2, answerV2
        );
        db.update("UPDATE rs_question SET current_version_id = ? WHERE id = ?", v2Id, qId);

        // Verify redo uses current_version_id (v2) even while wrong_question still points to v1
        PracticeDtos.PracticeView redoBeforeFailV2 = practices.createForQuestion(userId, wqId, prefix + "-redo-v2-check");
        assertEquals(String.valueOf(v2Id), redoBeforeFailV2.items().getFirst().questionVersionId());
        practices.cancel(userId, Long.parseLong(redoBeforeFailV2.id()), null);

        // 3. Fail on v2 in a new practice
        failQuestionInPractice(qId, "fail-v2");

        assertEquals(1L, db.queryForObject("SELECT count(*) FROM rs_wrong_question WHERE user_id = ? AND question_id = ?",
                Long.class, userId, qId));
        var wqAfter = db.queryForMap("SELECT wrong_count, question_version_id, knowledge_id, latest_attempt_id FROM rs_wrong_question WHERE id = ?", wqId);
        assertEquals(2, ((Number) wqAfter.get("wrong_count")).intValue());
        assertEquals(v2Id, ((Number) wqAfter.get("question_version_id")).longValue());
        assertEquals(kp2Id, ((Number) wqAfter.get("knowledge_id")).longValue());
        long secondAttemptId = ((Number) wqAfter.get("latest_attempt_id")).longValue();
        assertNotEquals(firstAttemptId, secondAttemptId);
        long maxAttemptId = db.queryForObject("SELECT max(id) FROM rs_wrong_attempt WHERE wrong_question_id = ?", Long.class, wqId);
        assertEquals(maxAttemptId, secondAttemptId);
    }

    private long failQuestionInPractice(long qId, String suffix) {
        long kp = db.queryForObject("SELECT v.knowledge_point_id FROM rs_question q JOIN rs_question_version v ON v.id = q.current_version_id WHERE q.id = ?",
                Long.class, qId);
        PracticeDtos.PracticeView p = practices.create(userId, prefix + "-" + suffix,
                new PracticeDtos.CreateRequest(List.of(String.valueOf(kp)), 1, null, null, null));
        long pId = Long.parseLong(p.id());
        practices.start(userId, pId, null);
        practices.submit(userId, pId, prefix + "-" + suffix + "-s",
                new PracticeDtos.SubmitRequest(List.of(new PracticeDtos.FinalAnswer(
                        p.items().getFirst().id(), json.valueToTree(Map.of("selectedOption", "D")), List.of()
                )), null, null, null));
        return db.queryForObject("SELECT id FROM rs_wrong_question WHERE user_id = ? AND question_id = ?",
                Long.class, userId, qId);
    }

    private long createUser(String username, BCryptPasswordEncoder encoder) {
        return db.queryForObject(
                "INSERT INTO rs_user(username, password_hash, role) VALUES (?, ?, 'STUDENT') RETURNING id",
                Long.class, username, encoder.encode(password)
        );
    }

    private Cookie login(String username) throws Exception {
        var res = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return res.getResponse().getCookie("RSID");
    }

    private long createKnowledge(long chapterId, String code, String name, int order) {
        return db.queryForObject(
                "INSERT INTO rs_knowledge_point(subject_id, chapter_id, code, name, sort_order) VALUES (1, ?, ?, ?, ?) RETURNING id",
                Long.class, chapterId, code, name, order
        );
    }

    private long createChoiceQuestion(long kpId, String no, String origin, String correctOption) {
        try {
            String qNo = (prefix + "-" + no).substring(0, Math.min(24, (prefix + "-" + no).length()));
            long qId = db.queryForObject(
                    "INSERT INTO rs_question(subject_id, question_no, origin, status) VALUES (1, ?, ?, 'ACTIVE') RETURNING id",
                    Long.class, qNo, origin
            );
            String content = json.writeValueAsString(Map.of(
                    "stem", "Stem for " + no,
                    "options", List.of(
                            Map.of("key", "A", "text", "Opt A"),
                            Map.of("key", "B", "text", "Opt B"),
                            Map.of("key", "C", "text", "Opt C"),
                            Map.of("key", "D", "text", "Opt D")
                    ),
                    "explanation", "Exp " + no
            ));
            String answer = json.writeValueAsString(Map.of("correctOption", correctOption));
            long vId = db.queryForObject(
                    "INSERT INTO rs_question_version(question_id, version, subject_id, knowledge_point_id, type, content_json, answer_json, max_score) VALUES (?, 1, 1, ?, 'CHOICE', CAST(? AS jsonb), CAST(? AS jsonb), 2) RETURNING id",
                    Long.class, qId, kpId, content, answer
            );
            db.update("UPDATE rs_question SET current_version_id = ? WHERE id = ?", vId, qId);
            return qId;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long createSubjectiveQuestion(long kpId, String no, String type) {
        try {
            String qNo = (prefix + "-" + no).substring(0, Math.min(24, (prefix + "-" + no).length()));
            long qId = db.queryForObject(
                    "INSERT INTO rs_question(subject_id, question_no, origin, status) VALUES (1, ?, 'MANUAL', 'ACTIVE') RETURNING id",
                    Long.class, qNo
            );
            String content = json.writeValueAsString(Map.of("stem", "Stem for " + no, "explanation", "Exp " + no));
            String answer = json.writeValueAsString(Map.of("referenceAnswer", "Ref " + no));
            String rubric = json.writeValueAsString(Map.of("items", List.of(Map.of("criterion", "Key point", "score", 10))));
            long vId = db.queryForObject(
                    "INSERT INTO rs_question_version(question_id, version, subject_id, knowledge_point_id, type, content_json, answer_json, rubric_json, max_score) VALUES (?, 1, 1, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), 10) RETURNING id",
                    Long.class, qId, kpId, type, content, answer, rubric
            );
            db.update("UPDATE rs_question SET current_version_id = ? WHERE id = ?", vId, qId);
            return qId;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
