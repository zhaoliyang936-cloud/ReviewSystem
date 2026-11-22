package cn.reviewsystem.wrong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Single transactional write entry point for wrong-question recording and status transitions.
 */
@Service
public class WrongQuestionRecorder {

    public enum Source {
        PRACTICE,
        TASK
    }

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public WrongQuestionRecorder(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public void record(
            long userId,
            Source source,
            Long practiceId,
            Long itemId,
            Long taskItemId,
            long questionId,
            long versionId,
            long knowledgeId,
            String questionType,
            Boolean passed,
            JsonNode answer,
            JsonNode grade,
            String attemptType,
            Long linkedWrongQuestionId
    ) {
        if (passed == null) {
            return;
        }
        if (source == Source.PRACTICE && (practiceId == null || itemId == null || taskItemId != null)) {
            throw new IllegalArgumentException("Invalid practice attempt owner");
        }
        if (source == Source.TASK && (practiceId != null || itemId != null || taskItemId == null)) {
            throw new IllegalArgumentException("Invalid task attempt owner");
        }

        var existing = db.query(
                "SELECT id FROM rs_wrong_question WHERE user_id = ? AND question_id = ? FOR UPDATE",
                (r, n) -> r.getLong(1),
                userId, questionId
        );

        if (!passed) {
            long wqId;
            if (existing.isEmpty()) {
                String origin = Objects.requireNonNull(db.queryForObject(
                        "SELECT origin FROM rs_question WHERE id = ?",
                        String.class,
                        questionId
                ));
                wqId = Objects.requireNonNull(db.queryForObject(
                        """
                        INSERT INTO rs_wrong_question(
                            user_id, question_id, question_version_id, knowledge_id,
                            source_type, status, wrong_count, first_wrong_at, last_wrong_at
                        ) VALUES (?, ?, ?, ?, ?, 'PENDING', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        RETURNING id
                        """,
                        Long.class,
                        userId, questionId, versionId, knowledgeId, origin
                ));
            } else {
                wqId = existing.getFirst();
                db.update(
                        """
                        UPDATE rs_wrong_question
                        SET wrong_count = wrong_count + 1,
                            last_wrong_at = CURRENT_TIMESTAMP,
                            question_version_id = ?,
                            knowledge_id = ?,
                            status = 'PENDING',
                            mastered_at = NULL
                        WHERE id = ?
                        """,
                        versionId, knowledgeId, wqId
                );
            }
            long attemptId = insertAttempt(wqId, practiceId, itemId, taskItemId, attemptType, answer, grade, false);
            db.update("UPDATE rs_wrong_question SET latest_attempt_id = ? WHERE id = ?", attemptId, wqId);
            return;
        }

        if (existing.isEmpty()) {
            return;
        }

        long wqId = existing.getFirst();
        long attemptId = insertAttempt(wqId, practiceId, itemId, taskItemId, attemptType, answer, grade, true);
        if ("ORIGINAL_REDO".equals(attemptType)
                && linkedWrongQuestionId != null
                && linkedWrongQuestionId.equals(wqId)) {
            db.update(
                    """
                    UPDATE rs_wrong_question
                    SET status = 'MASTERED',
                        mastered_at = CURRENT_TIMESTAMP,
                        latest_attempt_id = ?
                    WHERE id = ?
                    """,
                    attemptId, wqId
            );
        } else {
            db.update("UPDATE rs_wrong_question SET latest_attempt_id = ? WHERE id = ?", attemptId, wqId);
        }
    }

    private long insertAttempt(
            long wrongQuestionId,
            Long practiceId,
            Long itemId,
            Long taskItemId,
            String attemptType,
            JsonNode answer,
            JsonNode grade,
            boolean passed
    ) {
        return Objects.requireNonNull(db.queryForObject(
                """
                INSERT INTO rs_wrong_attempt(
                    wrong_question_id, practice_id, item_id, task_item_id,
                    attempt_type, answer_json, grade_json, passed
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                RETURNING id
                """,
                Long.class,
                wrongQuestionId,
                practiceId,
                itemId,
                taskItemId,
                attemptType,
                write(answer),
                write(grade),
                passed
        ));
    }

    private String write(JsonNode value) {
        try {
            return json.writeValueAsString(value == null ? NullNode.getInstance() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
