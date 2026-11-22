package cn.reviewsystem.practice;

import cn.reviewsystem.ai.AiJobService;
import cn.reviewsystem.ai.AiProperties;
import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.question.*;
import cn.reviewsystem.wrong.WrongQuestionRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeService {
    private static final Logger log = LoggerFactory.getLogger(PracticeService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final QuestionValidator validator;
    private final GradingService grading;
    private final PracticeMixConfig mix;
    private final AiAvailabilityPort ai;
    private final WrongQuestionRecorder wrongQuestions;
    private final AiJobService aiJobs;
    private final AiProperties aiProperties;

    public PracticeService(
            JdbcTemplate db,
            ObjectMapper json,
            QuestionValidator validator,
            GradingService grading,
            PracticeMixConfig mix,
            AiAvailabilityPort ai) {
        this(db, json, validator, grading, mix, ai, new WrongQuestionRecorder(db, json), null, null);
    }

    @Autowired
    public PracticeService(
            JdbcTemplate db,
            ObjectMapper json,
            QuestionValidator validator,
            GradingService grading,
            PracticeMixConfig mix,
            AiAvailabilityPort ai,
            WrongQuestionRecorder wrongQuestions,
            @Autowired(required = false) AiJobService aiJobs,
            @Autowired(required = false) AiProperties aiProperties) {
        this.db = db;
        this.json = json;
        this.validator = validator;
        this.grading = grading;
        this.mix = mix;
        this.ai = ai;
        this.wrongQuestions = wrongQuestions;
        this.aiJobs = aiJobs;
        this.aiProperties = aiProperties;
    }

    @Transactional
    public PracticeDtos.PracticeView create(long userId, String key, PracticeDtos.CreateRequest request) {
        requireKey(key);
        rejectClientScope(
                request == null ? null : request.userId(),
                request == null ? null : request.subjectId(),
                request == null ? null : request.sourceMode());
        if (request == null || request.questionCount() == null || request.questionCount() < 1 || request.questionCount() > 20) {
            validation("questionCount must be between 1 and 20");
        }
        if (request.knowledgeIds() == null || request.knowledgeIds().isEmpty()) {
            validation("knowledgeIds must not be empty");
        }
        db.query("SELECT pg_advisory_xact_lock(?)", r -> {}, userId);
        var prior = db.query(
                "SELECT id FROM rs_practice WHERE user_id=? AND request_key=?",
                (r, n) -> r.getLong(1),
                userId, key);
        if (!prior.isEmpty()) {
            return detail(userId, prior.get(0), null, request.questionCount());
        }

        LinkedHashSet<Long> knowledgeIds = new LinkedHashSet<>();
        for (String value : request.knowledgeIds()) {
            knowledgeIds.add(parseId(value));
        }
        if (knowledgeIds.isEmpty()) {
            validation("knowledgeIds must not be empty");
        }

        Map<Long, Picker> pickers = new LinkedHashMap<>();
        int bankAvailable = 0;
        for (long knowledgeId : knowledgeIds) {
            requireEnabledKnowledge(knowledgeId);
            List<Candidate> candidates = candidates(knowledgeId);
            Set<Long> answered = answered(userId, knowledgeId);
            Picker picker = new Picker(candidates, candidates.stream().filter(c -> !answered.contains(c.questionId())).toList());
            pickers.put(knowledgeId, picker);
            bankAvailable += picker.remaining();
        }
        if (bankAvailable == 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "NO_AVAILABLE_QUESTION",
                    "No available question for knowledge point " + knowledgeCode(knowledgeIds.iterator().next()));
        }

        AiAvailabilityPort.AiStatus aiStatus = ai != null ? ai.status(userId) : AiAvailabilityPort.AiStatus.no("AI_DISABLED");
        boolean aiAvailable = aiStatus.available() && aiJobs != null;
        int requestedCount = request.questionCount();
        var plannedSlots = MixSlotAllocator.allocate(requestedCount, mix.ai());
        int bankTarget = aiAvailable && plannedSlots.aiSlots() > 0
                ? Math.min(plannedSlots.bankSlots(), bankAvailable)
                : Math.min(requestedCount, bankAvailable);

        List<Candidate> selected = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        List<Long> order = new ArrayList<>(knowledgeIds);
        while (selected.size() < bankTarget) {
            boolean picked = false;
            for (long knowledgeId : order) {
                if (selected.size() >= bankTarget) {
                    break;
                }
                Candidate candidate = pickers.get(knowledgeId).next();
                if (candidate == null) {
                    continue;
                }
                if (!selectedIds.add(candidate.questionId())) {
                    throw new IllegalStateException("Practice picker selected duplicate questionId " + candidate.questionId());
                }
                selected.add(candidate);
                picked = true;
            }
            if (!picked) {
                break;
            }
        }

        int gap = requestedCount - selected.size();
        boolean preparing = aiAvailable && gap > 0;
        if (!preparing && bankAvailable < requestedCount) {
            log.warn("Practice question shortage: requested={}, bank={}, knowledgeIds={}", requestedCount, bankAvailable, knowledgeIds);
        }

        String activityStatus = preparing ? "PREPARING" : "READY";
        var activityIds = db.query(
                "INSERT INTO rs_learning_activity(user_id,subject_id,kind,status) VALUES (?,1,'PRACTICE',?) "
                        + "ON CONFLICT (user_id,subject_id) WHERE status IN ('PREPARING','READY','IN_PROGRESS','GRADING') DO NOTHING RETURNING id",
                (r, n) -> r.getLong(1),
                userId, activityStatus);
        if (activityIds.isEmpty()) {
            throwActive(userId);
        }
        long activityId = activityIds.get(0);

        ObjectNode ratio = json.createObjectNode();
        ratio.put("version", mix.version());
        ObjectNode configured = ratio.putObject("ratio");
        configured.put("bank", mix.bank());
        configured.put("ai", mix.ai());

        int practiceCount = preparing ? requestedCount : selected.size();
        int bankCount = selected.size();
        int bankShortfall = Math.max(0, plannedSlots.bankSlots() - bankCount);
        ObjectNode planned = ratio.putObject("planned");
        planned.put("bank", plannedSlots.bankSlots());
        planned.put("ai", plannedSlots.aiSlots());
        ObjectNode target = ratio.putObject("target");
        target.put("bank", bankCount);
        target.put("ai", requestedCount - bankCount);
        if (bankShortfall > 0 || (!preparing && bankCount < requestedCount)) {
            ObjectNode shortage = ratio.putObject("shortage");
            shortage.put("requested", requestedCount);
            shortage.put("actual", practiceCount);
            if (bankShortfall > 0) {
                shortage.put("bankShortfall", bankShortfall);
            }
            shortage.put("reason", bankShortfall > 0 ? "INSUFFICIENT_RECOMMENDATIONS" : "AI_UNAVAILABLE");
        }
        ObjectNode actual = ratio.putObject("actual");
        actual.put("bank", bankCount);
        actual.put("ai", 0);
        ArrayNode degraded = ratio.putArray("degraded");
        if (!preparing) {
            String degradeReason = aiStatus.reasonCode() == null ? "AI_DISABLED" : aiStatus.reasonCode();
            for (int i = 0; i < bankCount; i++) {
                if (plannedSlots.aiPositions().get(i)) {
                    ObjectNode entry = degraded.addObject();
                    entry.put("position", i);
                    entry.put("reason", degradeReason);
                }
            }
        }

        Long practiceId = Objects.requireNonNull(db.queryForObject(
                "INSERT INTO rs_practice(activity_id,user_id,subject_id,question_count,mix_ratio_json,request_key) "
                        + "VALUES (?,?,1,?,CAST(? AS jsonb),?) RETURNING id",
                Long.class,
                activityId, userId, practiceCount, write(ratio), key));

        for (int i = 0; i < selected.size(); i++) {
            Candidate c = selected.get(i);
            db.update(
                    "INSERT INTO rs_practice_item(practice_id,question_id,question_version_id,knowledge_id,question_type,max_score,position) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    practiceId, c.questionId(), c.versionId(), c.knowledgeId(), c.type(), c.maxScore(), i);
        }

        db.update("INSERT INTO rs_student_course_guard(user_id,subject_id) VALUES (?,1) ON CONFLICT DO NOTHING", userId);
        db.update("UPDATE rs_student_course_guard SET tutoring_epoch=tutoring_epoch+1 WHERE user_id=? AND subject_id=1", userId);

        if (preparing) {
            ObjectNode refs = json.createObjectNode();
            refs.put("kind", "PRACTICE");
            refs.put("practiceId", practiceId);
            refs.put("activityId", activityId);
            refs.put("requestedCount", requestedCount);
            refs.put("gap", gap);
            ArrayNode kpArr = refs.putArray("knowledgeIds");
            knowledgeIds.forEach(kpArr::add);
            ArrayNode bankArr = refs.putArray("bankQuestionIds");
            selected.forEach(c -> bankArr.add(c.questionId()));

            int timeoutSec = aiProperties != null ? Math.max(5, aiProperties.getPrepareTimeoutSeconds()) : 90;
            aiJobs.createJob(
                    userId,
                    "QUESTION_GENERATION",
                    AiJobService.sha256("practice:" + practiceId),
                    "practice:" + practiceId,
                    refs,
                    selected.get(0).versionId(),
                    selected.get(0).knowledgeId(),
                    null,
                    "QUEUED",
                    OffsetDateTime.now().plusSeconds(timeoutSec));
        }

        return detail(userId, practiceId, null, requestedCount);
    }

    @Transactional
    public PracticeDtos.PracticeView createForQuestion(long userId, long wrongQuestionId, String idempotencyKey) {
        requireKey(idempotencyKey);
        db.query("SELECT pg_advisory_xact_lock(?)", r -> {}, userId);
        var wqRows = db.query("SELECT question_id FROM rs_wrong_question WHERE id=? AND user_id=?", (r, n) -> r.getLong(1), wrongQuestionId, userId);
        if (wqRows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Wrong question not found");
        }
        long questionId = wqRows.getFirst();
        var prior = db.query("SELECT id,attempt_type,redo_wrong_question_id FROM rs_practice WHERE user_id=? AND request_key=?", (r, n) -> new Object[]{r.getLong(1), r.getString(2), r.getObject(3, Long.class)}, userId, idempotencyKey);
        if (!prior.isEmpty()) {
            Object[] p = prior.getFirst();
            if ("ORIGINAL_REDO".equals(p[1]) && wrongQuestionId == ((Long) p[2])) {
                return detail(userId, (Long) p[0], null, 1);
            }
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "The idempotency key was already used with a different request");
        }
        var qRows = db.query("SELECT q.status,q.current_version_id,v.knowledge_point_id,v.type,v.max_score FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id WHERE q.id=?", (r, n) -> new Object[]{r.getString(1), r.getLong(2), r.getLong(3), r.getString(4), r.getDouble(5)}, questionId);
        if (qRows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Question not found");
        }
        Object[] q = qRows.getFirst();
        if (!"ACTIVE".equals(q[0])) {
            throw new BusinessException(HttpStatus.CONFLICT, "QUESTION_SUSPENDED", "Question is suspended and cannot be redone");
        }
        long versionId = (Long) q[1], knowledgeId = (Long) q[2];
        String type = (String) q[3];
        double maxScore = (Double) q[4];
        var activityIds = db.query("INSERT INTO rs_learning_activity(user_id,subject_id,kind,status) VALUES (?,1,'PRACTICE','READY') ON CONFLICT (user_id,subject_id) WHERE status IN ('PREPARING','READY','IN_PROGRESS','GRADING') DO NOTHING RETURNING id", (r, n) -> r.getLong(1), userId);
        if (activityIds.isEmpty()) {
            throwActive(userId);
        }
        long activityId = activityIds.getFirst();
        ObjectNode ratio = json.createObjectNode();
        ratio.put("version", mix.version());
        ObjectNode configured = ratio.putObject("ratio");
        configured.put("bank", mix.bank());
        configured.put("ai", mix.ai());
        ObjectNode planned = ratio.putObject("planned");
        planned.put("bank", 1);
        planned.put("ai", 0);
        ObjectNode target = ratio.putObject("target");
        target.put("bank", 1);
        target.put("ai", 0);
        ObjectNode actual = ratio.putObject("actual");
        actual.put("bank", 1);
        actual.put("ai", 0);
        ratio.putArray("degraded");
        Long practiceId = db.queryForObject("INSERT INTO rs_practice(activity_id,user_id,subject_id,question_count,mix_ratio_json,request_key,attempt_type,redo_wrong_question_id) VALUES (?,?,1,1,CAST(? AS jsonb),?,'ORIGINAL_REDO',?) RETURNING id", Long.class, activityId, userId, write(ratio), idempotencyKey, wrongQuestionId);
        db.update("INSERT INTO rs_practice_item(practice_id,question_id,question_version_id,knowledge_id,question_type,max_score,position) VALUES (?,?,?,?,?,?,0)", practiceId, questionId, versionId, knowledgeId, type, maxScore);
        db.update("INSERT INTO rs_student_course_guard(user_id,subject_id) VALUES (?,1) ON CONFLICT DO NOTHING", userId);
        db.update("UPDATE rs_student_course_guard SET tutoring_epoch=tutoring_epoch+1 WHERE user_id=? AND subject_id=1", userId);
        return detail(userId, Objects.requireNonNull(practiceId), null, 1);
    }

    public PracticeDtos.PracticeView detail(long userId, long practiceId, String sourceMode) {
        return detail(userId, practiceId, sourceMode, null);
    }

    private PracticeDtos.PracticeView detail(long userId, long practiceId, String sourceMode, Integer requestedCount) {
        rejectClientScope(null, null, sourceMode);
        PracticeRow practice = practice(userId, practiceId, false);
        List<PracticeDtos.ItemView> items = "PREPARING".equals(practice.status())
                ? List.of()
                : db.query(
                        "SELECT pi.id,pi.position,pi.question_id,pi.question_version_id,pi.knowledge_id,pi.question_type,pi.max_score,pi.draft_json::text,pi.draft_version,v.content_json::text "
                                + "FROM rs_practice_item pi JOIN rs_question_version v ON v.id=pi.question_version_id WHERE pi.practice_id=? ORDER BY pi.position",
                        (r, n) -> itemView(userId, r),
                        practiceId);
        int prepTimeout = aiProperties != null ? Math.max(5, aiProperties.getPrepareTimeoutSeconds()) : 90;
        return new PracticeDtos.PracticeView(
                String.valueOf(practice.id()),
                String.valueOf(practice.activityId()),
                practice.status(),
                practice.questionCount(),
                requestedCount == null ? practice.questionCount() : requestedCount,
                prepTimeout,
                read(practice.mixRatio()),
                items,
                practice.attemptType(),
                practice.variantWrongQuestionId() == null ? null : String.valueOf(practice.variantWrongQuestionId()));
    }

    public Map<String, Object> preparationStatus(long userId, long jobId) {
        var rows = db.query(
                "SELECT id, state, error_code, output_json::text, snapshot_refs_json::text FROM rs_ai_job WHERE id=? AND user_id=? AND scenario='QUESTION_GENERATION'",
                (r, n) -> Map.<String, Object>of(
                        "jobId", String.valueOf(r.getLong(1)),
                        "state", r.getString(2),
                        "errorCode", r.getString(3) == null ? "" : r.getString(3),
                        "output", readNullable(r.getString(4)) == null ? json.nullNode() : read(r.getString(4)),
                        "refs", read(r.getString(5))),
                jobId, userId);
        if (rows.isEmpty()) {
            notFound();
        }
        return rows.get(0);
    }

    @Transactional
    public PracticeDtos.PracticeView start(long userId, long practiceId, String sourceMode) {
        rejectClientScope(null, null, sourceMode);
        PracticeRow row = practice(userId, practiceId, true);
        if (row.status().equals("READY")) {
            db.update("UPDATE rs_learning_activity SET status='IN_PROGRESS',updated_at=CURRENT_TIMESTAMP WHERE id=?", row.activityId());
        } else if (!row.status().equals("IN_PROGRESS")) {
            conflict("Practice cannot be started from status " + row.status());
        }
        return detail(userId, practiceId, null);
    }

    @Transactional
    public void cancel(long userId, long practiceId, String sourceMode) {
        rejectClientScope(null, null, sourceMode);
        PracticeRow row = practice(userId, practiceId, true);
        if (row.status().equals("CANCELLED")) {
            return;
        }
        if (!Set.of("PREPARING", "READY").contains(row.status())) {
            conflict("Only preparing or ready practices can be cancelled");
        }
        db.update("UPDATE rs_learning_activity SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=?", row.activityId());
    }

    @Transactional
    public PracticeDtos.DraftView draft(long userId, long practiceId, long itemId, PracticeDtos.DraftRequest request) {
        rejectClientScope(request == null ? null : request.userId(), request == null ? null : request.subjectId(), request == null ? null : request.sourceMode());
        if (request == null || request.draftVersion() == null) {
            validation("draftVersion is required");
        }
        PracticeRow row = practice(userId, practiceId, true);
        if (!row.status().equals("IN_PROGRESS")) {
            conflict("Drafts can only be changed while the practice is in progress");
        }
        if (request.answer() == null || !request.answer().isObject() || request.answer().isEmpty()) {
            validation("answer must be a non-empty JSON object");
        }
        int updated = db.update("UPDATE rs_practice_item SET draft_json=CAST(? AS jsonb),draft_version=draft_version+1 WHERE id=? AND practice_id=? AND draft_version=?", write(request.answer()), itemId, practiceId, request.draftVersion());
        if (updated == 0) {
            if (count("SELECT count(*) FROM rs_practice_item WHERE id=? AND practice_id=?", itemId, practiceId) == 0) {
                notFound();
            }
            throw new BusinessException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Draft was changed by another request");
        }
        return new PracticeDtos.DraftView(request.answer(), request.draftVersion() + 1);
    }

    @Transactional
    public PracticeDtos.ResultView submit(long userId, long practiceId, String key, PracticeDtos.SubmitRequest request) {
        requireKey(key);
        rejectClientScope(request == null ? null : request.userId(), request == null ? null : request.subjectId(), request == null ? null : request.sourceMode());
        if (request == null) {
            validation("Request body is required");
        }
        String hash = hash(request);
        PracticeRow row = practice(userId, practiceId, true);
        if (row.submittedAt() != null) {
            if (key.equals(row.submitKey()) && hash.equals(row.submitHash())) {
                return result(userId, practiceId, null);
            }
            if (key.equals(row.submitKey())) {
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "The idempotency key was already used with a different body");
            }
            conflict("Practice has already been submitted");
        }
        if (!row.status().equals("IN_PROGRESS")) {
            conflict("Practice must be in progress before submission");
        }
        Map<Long, PracticeDtos.FinalAnswer> answers = new HashMap<>();
        if (request.answers() != null) {
            for (var answer : request.answers()) {
                long itemId = parseId(answer.itemId());
                if (answers.put(itemId, answer) != null) {
                    validation("Duplicate practice item answer");
                }
                if (count("SELECT count(*) FROM rs_practice_item WHERE id=? AND practice_id=?", itemId, practiceId) == 0) {
                    validation("Answer item does not belong to the practice");
                }
            }
        }
        String attemptType = "ORIGINAL_REDO".equals(row.attemptType())
                ? "ORIGINAL_REDO"
                : "AI_VARIANT".equals(row.attemptType()) ? "AI_VARIANT" : "ORDINARY";
        Long linkedWrongQuestionId = "ORIGINAL_REDO".equals(attemptType)
                ? row.redoWrongQuestionId()
                : "AI_VARIANT".equals(attemptType) ? row.variantWrongQuestionId() : null;
        var items = db.query(
                "SELECT pi.id,pi.question_id,pi.question_version_id,v.knowledge_point_id,pi.question_type,pi.max_score,v.answer_json::text,v.rubric_json::text,v.content_json->>'stem' "
                        + "FROM rs_practice_item pi JOIN rs_question_version v ON v.id=pi.question_version_id WHERE pi.practice_id=? ORDER BY pi.position",
                (r, n) -> new GradeItem(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getString(5), r.getDouble(6), r.getString(7), r.getString(8), r.getString(9)),
                practiceId);
        for (GradeItem item : items) {
            var submitted = answers.get(item.id());
            JsonNode answer = submitted == null || submitted.answer() == null ? NullNode.getInstance() : submitted.answer();
            List<Long> submittedAssets = validateSubmittedAssets(userId, item.id(), submitted == null ? List.of() : submitted.assetIds());
            var grade = grading.grade(userId, item.versionId(), item.knowledgeId(), item.type(), item.maxScore(), item.stem(), answer, read(item.correctAnswer()), readNullable(item.rubric()));
            db.update("UPDATE rs_practice_item SET final_answer_json=CAST(? AS jsonb),result_json=CAST(? AS jsonb),grading_status=? WHERE id=?", write(answer), write(grade.result()), grade.status(), item.id());
            for (long assetId : submittedAssets) {
                db.update("UPDATE rs_answer_asset SET state='SUBMITTED' WHERE id=?", assetId);
            }
            JsonNode passedNode = grade.result().get("passed");
            Boolean passed = passedNode != null && passedNode.isBoolean() ? passedNode.asBoolean() : null;
            wrongQuestions.record(userId, WrongQuestionRecorder.Source.PRACTICE, practiceId, item.id(), null, item.questionId(), item.versionId(), item.knowledgeId(), item.type(), passed, answer, grade.result(), attemptType, linkedWrongQuestionId);
        }
        db.update("UPDATE rs_practice SET submit_key=?,submit_hash=?,submitted_at=CURRENT_TIMESTAMP WHERE id=?", key, hash, practiceId);
        db.update("UPDATE rs_learning_activity SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE id=?", row.activityId());
        db.update("INSERT INTO rs_student_course_guard(user_id,subject_id) VALUES (?,1) ON CONFLICT DO NOTHING", userId);
        db.update("UPDATE rs_student_course_guard SET learning_revision=learning_revision+1 WHERE user_id=? AND subject_id=1", userId);
        db.update("INSERT INTO rs_change_event(event_type,source_id,source_revision,state) VALUES ('PRACTICE_SUBMITTED',?,1,'PENDING')", practiceId);
        return result(userId, practiceId, null);
    }

    @Transactional
    public PracticeDtos.ResultView retryReview(long userId, long practiceId, long itemId) {
        PracticeRow row = practice(userId, practiceId, true);
        if (!"COMPLETED".equals(row.status())) {
            conflict("Retry review is only available after completion");
        }
        var items = db.query(
                "SELECT pi.id,pi.question_id,pi.question_version_id,v.knowledge_point_id,pi.question_type,pi.max_score,v.answer_json::text,v.rubric_json::text,v.content_json->>'stem',pi.final_answer_json::text,pi.grading_status "
                        + "FROM rs_practice_item pi JOIN rs_question_version v ON v.id=pi.question_version_id WHERE pi.practice_id=? AND pi.id=?",
                (r, n) -> new Object[]{
                        new GradeItem(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getString(5), r.getDouble(6), r.getString(7), r.getString(8), r.getString(9)),
                        r.getString(10),
                        r.getString(11)
                },
                practiceId, itemId);
        if (items.isEmpty()) {
            notFound();
        }
        GradeItem item = (GradeItem) items.get(0)[0];
        String finalAnswerText = (String) items.get(0)[1];
        String currentStatus = (String) items.get(0)[2];
        AiAvailabilityPort.AiStatus aiStatus = ai != null ? ai.status(userId) : AiAvailabilityPort.AiStatus.no("AI_DISABLED");
        if (!aiStatus.available()) {
            throw new BusinessException(HttpStatus.CONFLICT, "AI_UNAVAILABLE",
                    aiStatus.reasonCode() == null ? "AI_DISABLED" : aiStatus.reasonCode());
        }
        if (!Set.of("AI_REVIEW_FAILED", "AI_REVIEW_PENDING").contains(currentStatus)) {
            conflict("Item is not in AI_REVIEW_FAILED status");
        }
        JsonNode answer = finalAnswerText == null ? NullNode.getInstance() : read(finalAnswerText);
        var grade = grading.grade(userId, item.versionId(), item.knowledgeId(), item.type(), item.maxScore(), item.stem(), answer, read(item.correctAnswer()), readNullable(item.rubric()));
        db.update("UPDATE rs_practice_item SET result_json=CAST(? AS jsonb),grading_status=? WHERE id=?", write(grade.result()), grade.status(), item.id());
        JsonNode passedNode = grade.result().get("passed");
        Boolean passed = passedNode != null && passedNode.isBoolean() ? passedNode.asBoolean() : null;
        if (passed != null) {
            String attemptType = "ORIGINAL_REDO".equals(row.attemptType())
                    ? "ORIGINAL_REDO"
                    : "AI_VARIANT".equals(row.attemptType()) ? "AI_VARIANT" : "ORDINARY";
            Long linkedWrongQuestionId = "ORIGINAL_REDO".equals(attemptType)
                    ? row.redoWrongQuestionId()
                    : "AI_VARIANT".equals(attemptType) ? row.variantWrongQuestionId() : null;
            wrongQuestions.record(userId, WrongQuestionRecorder.Source.PRACTICE, practiceId, item.id(), null, item.questionId(), item.versionId(), item.knowledgeId(), item.type(), passed, answer, grade.result(), attemptType, linkedWrongQuestionId);
            Integer maxRev = db.queryForObject("SELECT COALESCE(MAX(source_revision), 0) FROM rs_change_event WHERE event_type='PRACTICE_SUBMITTED' AND source_id=?", Integer.class, practiceId);
            int nextRev = (maxRev == null ? 0 : maxRev) + 1;
            db.update("INSERT INTO rs_change_event(event_type,source_id,source_revision,state) VALUES ('PRACTICE_SUBMITTED',?,?,'PENDING')", practiceId, nextRev);
        }
        return result(userId, practiceId, null);
    }

    public PracticeDtos.ResultView result(long userId, long practiceId, String sourceMode) {
        rejectClientScope(null, null, sourceMode);
        PracticeRow row = practice(userId, practiceId, false);
        if (!row.status().equals("COMPLETED")) {
            conflict("Practice result is available only after completion");
        }
        var items = db.query("SELECT pi.id,pi.position,pi.question_type,pi.max_score,pi.final_answer_json::text,pi.result_json::text,v.content_json::text,v.answer_json::text,v.rubric_json::text,wq.id FROM rs_practice_item pi JOIN rs_question_version v ON v.id=pi.question_version_id LEFT JOIN rs_wrong_question wq ON wq.user_id=? AND wq.question_id=pi.question_id WHERE pi.practice_id=? ORDER BY pi.position", (r, n) -> {
            JsonNode stored = read(r.getString(7));
            String explanation = stored.path("explanation").isTextual() ? stored.path("explanation").asText() : "";
            Long wqId = r.getObject(10, Long.class);
            return new PracticeDtos.ResultItem(String.valueOf(r.getLong(1)), r.getInt(2), r.getString(3), r.getDouble(4), publicContent(stored), readNullable(r.getString(5)), read(r.getString(8)), readNullable(r.getString(9)), explanation, readNullable(r.getString(6)), submittedAssets(userId, r.getLong(1)), wqId == null ? null : String.valueOf(wqId));
        }, userId, practiceId);
        return new PracticeDtos.ResultView(String.valueOf(row.id()), String.valueOf(row.activityId()), row.status(), row.submittedAt(), items);
    }

    public PageResult<PracticeDtos.HistoryItem> history(long userId, int page, int size, String sourceMode) {
        rejectClientScope(null, null, sourceMode);
        if (page < 0) validation("page must not be negative");
        if (size < 1) validation("size must be positive");
        int actual = Math.min(size, 100);
        Long total = db.queryForObject("SELECT count(*) FROM rs_practice WHERE user_id=? AND submitted_at IS NOT NULL", Long.class, userId);
        var items = db.query("SELECT p.id,p.activity_id,a.status,p.question_count,p.mix_ratio_json::text,p.created_at,p.submitted_at FROM rs_practice p JOIN rs_learning_activity a ON a.id=p.activity_id WHERE p.user_id=? AND p.submitted_at IS NOT NULL ORDER BY p.submitted_at DESC,p.id DESC LIMIT ? OFFSET ?", (r, n) -> new PracticeDtos.HistoryItem(String.valueOf(r.getLong(1)), String.valueOf(r.getLong(2)), r.getString(3), r.getInt(4), read(r.getString(5)), r.getObject(6, OffsetDateTime.class), r.getObject(7, OffsetDateTime.class)), userId, actual, page * actual);
        return new PageResult<>(items, page, actual, total == null ? 0 : total);
    }

    private PracticeRow practice(long userId, long id, boolean lock) {
        var rows = db.query("SELECT p.id,p.activity_id,a.status,p.question_count,p.mix_ratio_json::text,p.submit_key,p.submit_hash,p.submitted_at,p.attempt_type,p.redo_wrong_question_id,p.variant_wrong_question_id FROM rs_practice p JOIN rs_learning_activity a ON a.id=p.activity_id WHERE p.id=? AND p.user_id=?" + (lock ? " FOR UPDATE" : ""), (r, n) -> new PracticeRow(r.getLong(1), r.getLong(2), r.getString(3), r.getInt(4), r.getString(5), r.getString(6), r.getString(7), r.getObject(8, OffsetDateTime.class), r.getString(9), r.getObject(10, Long.class), r.getObject(11, Long.class)), id, userId);
        if (rows.isEmpty()) notFound();
        return rows.get(0);
    }

    private PracticeDtos.ItemView itemView(long userId, ResultSet r) throws SQLException {
        long itemId = r.getLong(1), versionId = r.getLong(4);
        return new PracticeDtos.ItemView(String.valueOf(itemId), r.getInt(2), String.valueOf(r.getLong(3)), String.valueOf(versionId), String.valueOf(r.getLong(5)), r.getString(6), r.getDouble(7), publicContent(read(r.getString(10))), questionAssets(versionId), readNullable(r.getString(8)), r.getInt(9), assets(userId, itemId));
    }

    private List<PracticeDtos.QuestionAssetView> questionAssets(long versionId) {
        return db.query("SELECT id,position,mime FROM rs_question_asset WHERE question_version_id=? ORDER BY position", (r, n) -> new PracticeDtos.QuestionAssetView(String.valueOf(r.getLong(1)), r.getInt(2), r.getString(3), "/api/question-assets/" + r.getLong(1)), versionId);
    }

    private List<PracticeDtos.AssetView> assets(long userId, long itemId) {
        return db.query("SELECT id,position,mime,size_bytes FROM rs_answer_asset WHERE practice_item_id=? AND user_id=? ORDER BY position", (r, n) -> new PracticeDtos.AssetView(String.valueOf(r.getLong(1)), r.getInt(2), r.getString(3), r.getLong(4), "/api/practice-assets/" + r.getLong(1)), itemId, userId);
    }

    private List<PracticeDtos.AssetView> submittedAssets(long userId, long itemId) {
        return db.query("SELECT id,position,mime,size_bytes FROM rs_answer_asset WHERE practice_item_id=? AND user_id=? AND state='SUBMITTED' ORDER BY position", (r, n) -> new PracticeDtos.AssetView(String.valueOf(r.getLong(1)), r.getInt(2), r.getString(3), r.getLong(4), "/api/practice-assets/" + r.getLong(1)), itemId, userId);
    }

    private List<Long> validateSubmittedAssets(long userId, long itemId, List<String> ids) {
        if (ids == null) return List.of();
        var unique = new LinkedHashSet<Long>();
        for (String value : ids) {
            long id = parseId(value);
            if (!unique.add(id)) validation("Duplicate answer asset");
            if (count("SELECT count(*) FROM rs_answer_asset WHERE id=? AND user_id=? AND practice_item_id=?", id, userId, itemId) == 0) validation("Answer asset does not belong to the practice item");
        }
        return List.copyOf(unique);
    }

    private void requireEnabledKnowledge(long id) {
        if (count("SELECT count(*) FROM rs_knowledge_point k JOIN rs_chapter c ON c.id=k.chapter_id WHERE k.id=? AND k.subject_id=1 AND k.enabled AND c.enabled", id) == 0)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BINDING", "Knowledge point does not exist or is disabled");
    }

    private String knowledgeCode(long id) {
        return db.queryForObject("SELECT code FROM rs_knowledge_point WHERE id=?", String.class, id);
    }

    private List<Candidate> candidates(long kp) {
        var rows = db.query("SELECT q.id,v.id,v.knowledge_point_id,v.type,v.max_score,v.content_json::text,v.answer_json::text,v.rubric_json::text,v.source_ref FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id WHERE q.subject_id=1 AND q.status='ACTIVE' AND NOT EXISTS (SELECT 1 FROM rs_question_review_case rc WHERE rc.question_id = q.id AND rc.status = 'OPEN') AND v.knowledge_point_id=? ORDER BY q.id", (r, n) -> new Candidate(r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4), r.getDouble(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9)), kp);
        return rows.stream().filter(this::valid).toList();
    }

    private boolean valid(Candidate c) {
        try {
            JsonNode stored = read(c.content());
            String explanation = stored.path("explanation").isTextual() ? stored.path("explanation").asText() : "";
            validator.validate(new QuestionWriteRequest(String.valueOf(c.knowledgeId()), c.type(), publicContent(stored), read(c.answer()), readNullable(c.rubric()), explanation, c.sourceRef(), null, c.maxScore(), null));
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private Set<Long> answered(long userId, long kp) {
        return new HashSet<>(db.query("SELECT DISTINCT pi.question_id FROM rs_practice_item pi JOIN rs_practice p ON p.id=pi.practice_id JOIN rs_learning_activity a ON a.id=p.activity_id WHERE p.user_id=? AND a.status='COMPLETED' AND pi.knowledge_id=?", (r, n) -> r.getLong(1), userId, kp));
    }

    private void throwActive(long userId) {
        var rows = db.query("SELECT id,kind FROM rs_learning_activity WHERE user_id=? AND subject_id=1 AND status IN ('PREPARING','READY','IN_PROGRESS','GRADING') ORDER BY id LIMIT 1", (r, n) -> new String[]{r.getString(1), r.getString(2)}, userId);
        if (rows.isEmpty()) conflict("An active learning activity already exists");
        throw new ActiveActivityException(rows.get(0)[0], rows.get(0)[1]);
    }

    private static void rejectClientScope(String userId, String subjectId, String sourceMode) {
        if (userId != null || subjectId != null || sourceMode != null) validation("userId, subjectId and sourceMode are server controlled");
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 120) validation("Idempotency-Key is required and must not exceed 120 characters");
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode publicContent(JsonNode source) {
        if (source == null || !source.isObject()) return source;
        ObjectNode copy = (ObjectNode) source.deepCopy();
        copy.remove("explanation");
        return copy;
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readNullable(String value) {
        return value == null ? null : read(value);
    }

    private String write(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String sql, Object... args) {
        Long count = db.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private static long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            validation("Invalid identifier");
            return 0;
        }
    }

    private static void validation(String message) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private static void conflict(String message) {
        throw new BusinessException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
    }

    private static void notFound() {
        throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Practice not found");
    }

    record PracticeRow(long id, long activityId, String status, int questionCount, String mixRatio, String submitKey, String submitHash, OffsetDateTime submittedAt, String attemptType, Long redoWrongQuestionId, Long variantWrongQuestionId) {}
    record Candidate(long questionId, long versionId, long knowledgeId, String type, double maxScore, String content, String answer, String rubric, String sourceRef) {}
    record GradeItem(long id, long questionId, long versionId, long knowledgeId, String type, double maxScore, String correctAnswer, String rubric, String stem) {}

    static class Picker {
        final List<Candidate> all, unanswered;
        final Set<Long> used = new HashSet<>();
        int unansweredIndex, allIndex;

        Picker(List<Candidate> all, List<Candidate> unanswered) {
            this.all = all;
            this.unanswered = unanswered;
        }

        Candidate next() {
            while (unansweredIndex < unanswered.size()) {
                Candidate value = unanswered.get(unansweredIndex++);
                if (used.add(value.questionId())) return value;
            }
            while (allIndex < all.size()) {
                Candidate value = all.get(allIndex++);
                if (used.add(value.questionId())) return value;
            }
            return null;
        }

        int remaining() {
            int count = 0;
            Set<Long> remainingIds = new HashSet<>(used);
            for (int i = unansweredIndex; i < unanswered.size(); i++) if (remainingIds.add(unanswered.get(i).questionId())) count++;
            for (int i = allIndex; i < all.size(); i++) if (remainingIds.add(all.get(i).questionId())) count++;
            return count;
        }
    }
}
