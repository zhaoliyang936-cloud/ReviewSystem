package cn.reviewsystem.question;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.security.IdentityService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminReviewCaseController {
    private static final Set<String> VALID_STATUSES = Set.of("OPEN", "RESOLVED", "ALL");

    private final JdbcTemplate db;

    public AdminReviewCaseController(JdbcTemplate db) {
        this.db = db;
    }

    public record FeedbackItem(
            String id,
            String userId,
            String username,
            String category,
            String comment,
            String status,
            OffsetDateTime createdAt) {}

    public record ReviewCaseView(
            String id,
            String questionId,
            String questionNo,
            String stemSummary,
            int triggerFeedbackCount,
            String reason,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime resolvedAt,
            List<FeedbackItem> feedbackList) {}

    @GetMapping("/api/admin/review-cases")
    public PageResult<ReviewCaseView> list(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid pagination");
        }
        String effectiveStatus = status == null || status.isBlank() ? "OPEN" : status.trim();
        if (!VALID_STATUSES.contains(effectiveStatus)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid status filter");
        }
        int actualSize = Math.min(size, 100);
        String where = "ALL".equals(effectiveStatus) ? "" : " WHERE rc.status = ?";
        Long total = "ALL".equals(effectiveStatus)
                ? db.queryForObject("SELECT count(*) FROM rs_question_review_case rc", Long.class)
                : db.queryForObject("SELECT count(*) FROM rs_question_review_case rc" + where, Long.class, effectiveStatus);

        String sql = """
                SELECT rc.id, rc.question_id, q.question_no, v.content_json->>'stem' AS stem,
                       rc.trigger_feedback_count, rc.reason, rc.status, rc.created_at, rc.resolved_at
                FROM rs_question_review_case rc
                JOIN rs_question q ON q.id = rc.question_id
                JOIN rs_question_version v ON v.id = q.current_version_id
                """ + where + " ORDER BY rc.created_at DESC, rc.id DESC LIMIT ? OFFSET ?";

        List<ReviewCaseView> items = "ALL".equals(effectiveStatus)
                ? db.query(sql, (r, n) -> mapCase(r), actualSize, page * actualSize)
                : db.query(sql, (r, n) -> mapCase(r), effectiveStatus, actualSize, page * actualSize);

        return new PageResult<>(items, page, actualSize, total == null ? 0 : total);
    }

    @PostMapping("/api/admin/review-cases/{id}/resolve")
    @Transactional
    public ReviewCaseView resolve(Authentication authentication, @PathVariable long id) {
        long adminId = ((IdentityService.User) authentication.getPrincipal()).id();
        int updated = db.update(
                """
                UPDATE rs_question_review_case
                SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP, resolved_by = ?
                WHERE id = ? AND status = 'OPEN'
                """,
                adminId, id);
        if (updated == 0) {
            Long exists = db.queryForObject("SELECT count(*) FROM rs_question_review_case WHERE id = ?", Long.class, id);
            if (exists == null || exists == 0) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Review case not found");
            }
        }
        var rows = db.query(
                """
                SELECT rc.id, rc.question_id, q.question_no, v.content_json->>'stem' AS stem,
                       rc.trigger_feedback_count, rc.reason, rc.status, rc.created_at, rc.resolved_at
                FROM rs_question_review_case rc
                JOIN rs_question q ON q.id = rc.question_id
                JOIN rs_question_version v ON v.id = q.current_version_id
                WHERE rc.id = ?
                """,
                (r, n) -> mapCase(r),
                id);
        return rows.get(0);
    }

    private ReviewCaseView mapCase(java.sql.ResultSet r) throws java.sql.SQLException {
        long questionId = r.getLong("question_id");
        String stem = r.getString("stem");
        String summary = stem == null ? "" : (stem.length() > 120 ? stem.substring(0, 120) + "..." : stem);
        List<FeedbackItem> feedbacks = db.query(
                """
                SELECT f.id, f.user_id, u.username, f.category, f.comment, f.status, f.created_at
                FROM rs_question_feedback f
                JOIN rs_user u ON u.id = f.user_id
                WHERE f.question_id = ?
                ORDER BY f.created_at DESC, f.id DESC
                """,
                (fr, fn) -> new FeedbackItem(
                        String.valueOf(fr.getLong("id")),
                        String.valueOf(fr.getLong("user_id")),
                        fr.getString("username"),
                        fr.getString("category"),
                        fr.getString("comment"),
                        fr.getString("status"),
                        fr.getObject("created_at", OffsetDateTime.class)),
                questionId);
        return new ReviewCaseView(
                String.valueOf(r.getLong("id")),
                String.valueOf(questionId),
                r.getString("question_no"),
                summary,
                r.getInt("trigger_feedback_count"),
                r.getString("reason"),
                r.getString("status"),
                r.getObject("created_at", OffsetDateTime.class),
                r.getObject("resolved_at", OffsetDateTime.class),
                feedbacks);
    }
}
