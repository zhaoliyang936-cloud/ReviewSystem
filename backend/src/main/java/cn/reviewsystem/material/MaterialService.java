package cn.reviewsystem.material;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.course.CourseService;
import cn.reviewsystem.material.MaterialDtos.MaterialChunkView;
import cn.reviewsystem.material.MaterialDtos.MaterialDetail;
import cn.reviewsystem.material.MaterialDtos.MaterialListItem;
import cn.reviewsystem.material.MaterialDtos.MaterialVersionView;
import cn.reviewsystem.material.MaterialDtos.MaterialWriteRequest;
import cn.reviewsystem.material.MaterialDtos.PublishRequest;
import cn.reviewsystem.material.MaterialDtos.WithdrawRequest;
import cn.reviewsystem.question.PageResult;
import cn.reviewsystem.security.IdentityService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialService {

    private final JdbcTemplate db;
    private final CourseService courses;
    private final MaterialChunkSplitter splitter;

    public MaterialService(JdbcTemplate db, CourseService courses, MaterialChunkSplitter splitter) {
        this.db = db;
        this.courses = courses;
        this.splitter = splitter;
    }

    public PageResult<MaterialListItem> list(String knowledgeId, String status, int page, int size) {
        if (page < 0) {
            validation("page must not be negative");
        }
        if (size < 1) {
            validation("size must be positive");
        }
        int actualSize = Math.min(size, 100);

        StringBuilder where = new StringBuilder("""
            WHERE mv.subject_id = 1
              AND NOT EXISTS (
                  SELECT 1 FROM rs_material_version mv2
                  WHERE mv2.material_id = mv.material_id AND mv2.version > mv.version
              )
            """);
        List<Object> args = new ArrayList<>();

        if (knowledgeId != null && !knowledgeId.isBlank()) {
            where.append(" AND mv.knowledge_id = ?");
            args.add(parseId(knowledgeId));
        }
        if (status != null && !status.isBlank()) {
            PublicationStatus parsedStatus;
            try {
                parsedStatus = PublicationStatus.valueOf(status.trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid publication status");
            }
            where.append(" AND mv.publication_status = ?");
            args.add(parsedStatus.name());
        }

        String fromSql = " FROM rs_material_version mv JOIN rs_knowledge_point k ON k.id = mv.knowledge_id ";
        Long total = db.queryForObject("SELECT count(*)" + fromSql + where, Long.class, args.toArray());

        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(actualSize);
        queryArgs.add(page * actualSize);

        String selectSql = """
            SELECT mv.material_id,
                   mv.version AS latest_version,
                   (SELECT count(*) FROM rs_material_version mv3 WHERE mv3.material_id = mv.material_id) AS version_count,
                   (SELECT max(mv4.version) FROM rs_material_version mv4
                     WHERE mv4.material_id = mv.material_id AND mv4.publication_status = 'PUBLISHED') AS published_version,
                   mv.knowledge_id,
                   k.name AS knowledge_name,
                   mv.title,
                   mv.source_label,
                   mv.publication_status,
                   mv.approved_at,
                   mv.created_at
            """ + fromSql + where + " ORDER BY mv.created_at DESC, mv.material_id DESC LIMIT ? OFFSET ?";

        List<MaterialListItem> items = db.query(selectSql, (rs, rowNum) -> new MaterialListItem(
            String.valueOf(rs.getLong("material_id")),
            rs.getInt("latest_version"),
            rs.getInt("version_count"),
            String.valueOf(rs.getLong("knowledge_id")),
            rs.getString("knowledge_name"),
            rs.getString("title"),
            rs.getString("source_label"),
            PublicationStatus.valueOf(rs.getString("publication_status")),
            rs.getObject("published_version", Integer.class),
            toInstant(rs.getTimestamp("approved_at")),
            toInstant(rs.getTimestamp("created_at"))
        ), queryArgs.toArray());

        return new PageResult<>(items, page, actualSize, total == null ? 0L : total);
    }

    public MaterialDetail detail(long materialId) {
        return detail(materialId, null);
    }

    private MaterialDetail detail(long materialId, String withdrawReason) {
        List<MaterialVersionView> versions = db.query("""
            SELECT mv.id, mv.version, mv.knowledge_id, k.name AS knowledge_name,
                   mv.title, mv.source_label, mv.content, mv.publication_status,
                   mv.approved_by, mv.approved_at, mv.created_by, mv.created_at
            FROM rs_material_version mv
            JOIN rs_knowledge_point k ON k.id = mv.knowledge_id
            WHERE mv.material_id = ? AND mv.subject_id = 1
            ORDER BY mv.version DESC
            """, (rs, rowNum) -> mapVersion(rs), materialId);

        if (versions.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Material not found");
        }

        MaterialVersionView latest = versions.get(0);
        long latestVersionId = Long.parseLong(latest.id());
        Integer publishedVersion = db.queryForObject("""
            SELECT max(version) FROM rs_material_version
            WHERE material_id = ? AND subject_id = 1 AND publication_status = 'PUBLISHED'
            """, Integer.class, materialId);
        String effectiveWithdrawReason = withdrawReason == null ? loadLatestWithdrawReason(materialId) : withdrawReason;
        List<MaterialChunkView> chunks = db.query("""
            SELECT id, position, text
            FROM rs_material_chunk
            WHERE material_version_id = ?
            ORDER BY position ASC
            """, (rs, rowNum) -> new MaterialChunkView(
            String.valueOf(rs.getLong("id")),
            rs.getInt("position"),
            rs.getString("text")
        ), latestVersionId);

        return new MaterialDetail(
            String.valueOf(materialId),
            latest.version(),
            versions.size(),
            latest.knowledgeId(),
            latest.knowledgeName(),
            latest.title(),
            latest.sourceLabel(),
            latest.content(),
            latest.publicationStatus(),
            publishedVersion,
            latest.approvedBy(),
            latest.approvedAt(),
            latest.createdAt(),
            effectiveWithdrawReason,
            chunks,
            versions
        );
    }

    private String loadLatestWithdrawReason(long materialId) {
        List<String> rows = db.query("""
            SELECT withdraw_reason FROM rs_material_version
            WHERE material_id = ? AND withdraw_reason IS NOT NULL
            ORDER BY version DESC
            LIMIT 1
            """, (rs, rowNum) -> rs.getString(1), materialId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public MaterialDetail create(MaterialWriteRequest req) {
        if (req == null) {
            validation("Request body is required");
        }
        long knowledgeId = parseId(req.knowledgeId());
        courses.requireEnabledKnowledgePoint(knowledgeId);
        String title = validateTitle(req.title());
        String sourceLabel = validateSourceLabel(req.sourceLabel(), false);
        String content = validateContent(req.content());

        Long materialId = db.queryForObject("SELECT nextval('rs_material_id_seq')", Long.class);
        if (materialId == null) {
            throw new IllegalStateException("Failed to allocate material_id");
        }
        Long userId = currentUserId();

        db.update("""
            INSERT INTO rs_material_version (
                material_id, version, subject_id, knowledge_id,
                title, source_label, content, publication_status, created_by
            ) VALUES (?, 1, 1, ?, ?, ?, ?, ?, ?)
            """,
            materialId, knowledgeId, title, sourceLabel, content,
            PublicationStatus.DRAFT.name(), userId
        );

        return detail(materialId);
    }

    @Transactional
    public MaterialDetail update(long materialId, MaterialWriteRequest req) {
        if (req == null || req.expectedVersion() == null) {
            validation("expectedVersion is required");
        }
        VersionRow latest = lockAndLoadLatest(materialId);
        if (latest.version() != req.expectedVersion()) {
            throw new BusinessException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Material was changed by another request");
        }

        long knowledgeId = parseId(req.knowledgeId());
        if (knowledgeId != latest.knowledgeId()) {
            courses.requireEnabledKnowledgePoint(knowledgeId);
        }
        String title = validateTitle(req.title());
        String sourceLabel = validateSourceLabel(req.sourceLabel(), false);
        String content = validateContent(req.content());
        Long userId = currentUserId();

        int nextVersion = latest.version() + 1;
        db.update("""
            INSERT INTO rs_material_version (
                material_id, version, subject_id, knowledge_id,
                title, source_label, content, publication_status, created_by
            ) VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?)
            """,
            materialId, nextVersion, knowledgeId, title, sourceLabel, content,
            PublicationStatus.DRAFT.name(), userId
        );

        return detail(materialId);
    }

    @Transactional
    public MaterialDetail publish(long materialId, PublishRequest req) {
        if (req == null || req.expectedVersion() == null) {
            validation("expectedVersion is required");
        }
        VersionRow latest = lockAndLoadLatest(materialId);
        if (latest.version() != req.expectedVersion()) {
            throw new BusinessException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Material was changed by another request");
        }
        if (latest.status() != PublicationStatus.DRAFT) {
            throw new BusinessException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Only DRAFT material version can be published");
        }
        if (latest.sourceLabel() == null || latest.sourceLabel().isBlank()) {
            validation("sourceLabel is required before publishing");
        }
        courses.requireEnabledKnowledgePoint(latest.knowledgeId());

        List<MaterialChunkSplitter.Chunk> chunks = splitter.split(latest.content());
        db.update("DELETE FROM rs_material_chunk WHERE material_version_id = ?", latest.id());
        db.batchUpdate(
            "INSERT INTO rs_material_chunk (material_version_id, position, text) VALUES (?, ?, ?)",
            chunks,
            chunks.size(),
            (ps, chunk) -> {
                ps.setLong(1, latest.id());
                ps.setInt(2, chunk.position());
                ps.setString(3, chunk.text());
            }
        );

        List<Integer> persistedPositions = db.query(
            "SELECT position FROM rs_material_chunk WHERE material_version_id = ? ORDER BY position ASC",
            (rs, rowNum) -> rs.getInt(1),
            latest.id()
        );
        if (persistedPositions.size() != chunks.size()) {
            throw new IllegalStateException("Material chunk count mismatch after insert");
        }
        for (int i = 0; i < persistedPositions.size(); i++) {
            if (persistedPositions.get(i) != i) {
                throw new IllegalStateException("Material chunk positions are not contiguous at index " + i);
            }
        }

        Long userId = currentUserId();
        db.update("""
            UPDATE rs_material_version
            SET publication_status = ?, approved_by = ?, approved_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            PublicationStatus.PUBLISHED.name(), userId, latest.id()
        );

        return detail(materialId);
    }

    @Transactional
    public MaterialDetail withdraw(long materialId, WithdrawRequest req) {
        if (req == null || req.expectedVersion() == null) {
            validation("expectedVersion is required");
        }
        VersionRow latest = lockAndLoadLatest(materialId);
        if (latest.version() != req.expectedVersion()) {
            throw new BusinessException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Material was changed by another request");
        }
        String reason = validateWithdrawReason(req.reason());
        VersionRow target = loadLatestPublished(materialId);
        if (target == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "STATE_CONFLICT", "No published material version to withdraw");
        }
        Long userId = currentUserId();

        db.update("""
            UPDATE rs_material_version
            SET publication_status = ?, withdraw_reason = ?, withdrawn_by = ?, withdrawn_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            PublicationStatus.WITHDRAWN.name(), reason, userId, target.id()
        );

        return detail(materialId, reason);
    }

    private VersionRow loadLatestPublished(long materialId) {
        List<VersionRow> rows = db.query("""
            SELECT id, material_id, version, knowledge_id, source_label, content, publication_status
            FROM rs_material_version
            WHERE material_id = ? AND subject_id = 1 AND publication_status = 'PUBLISHED'
            ORDER BY version DESC
            LIMIT 1
            FOR UPDATE
            """, (rs, rowNum) -> new VersionRow(
            rs.getLong("id"),
            rs.getLong("material_id"),
            rs.getInt("version"),
            rs.getLong("knowledge_id"),
            rs.getString("source_label"),
            rs.getString("content"),
            PublicationStatus.valueOf(rs.getString("publication_status"))
        ), materialId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private VersionRow lockAndLoadLatest(long materialId) {
        List<VersionRow> rows = db.query("""
            SELECT id, material_id, version, knowledge_id, source_label, content, publication_status
            FROM rs_material_version
            WHERE material_id = ? AND subject_id = 1
            ORDER BY version DESC
            LIMIT 1
            FOR UPDATE
            """, (rs, rowNum) -> new VersionRow(
            rs.getLong("id"),
            rs.getLong("material_id"),
            rs.getInt("version"),
            rs.getLong("knowledge_id"),
            rs.getString("source_label"),
            rs.getString("content"),
            PublicationStatus.valueOf(rs.getString("publication_status"))
        ), materialId);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Material not found");
        }
        return rows.get(0);
    }

    private MaterialVersionView mapVersion(ResultSet rs) throws SQLException {
        Long approvedBy = rs.getObject("approved_by", Long.class);
        Long createdBy = rs.getObject("created_by", Long.class);
        return new MaterialVersionView(
            String.valueOf(rs.getLong("id")),
            rs.getInt("version"),
            String.valueOf(rs.getLong("knowledge_id")),
            rs.getString("knowledge_name"),
            rs.getString("title"),
            rs.getString("source_label"),
            rs.getString("content"),
            PublicationStatus.valueOf(rs.getString("publication_status")),
            approvedBy == null ? null : String.valueOf(approvedBy),
            toInstant(rs.getTimestamp("approved_at")),
            createdBy == null ? null : String.valueOf(createdBy),
            toInstant(rs.getTimestamp("created_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof IdentityService.User user) {
            return user.id();
        }
        return null;
    }

    private static long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BINDING", "Knowledge point id is required");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BINDING", "Invalid knowledge point id");
        }
    }

    private static String validateTitle(String title) {
        if (title == null || title.isBlank()) {
            validation("title is required");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 200) {
            validation("title must not exceed 200 characters");
        }
        return trimmed;
    }

    private static String validateSourceLabel(String sourceLabel, boolean required) {
        if (sourceLabel == null || sourceLabel.isBlank()) {
            if (required) {
                validation("sourceLabel is required");
            }
            return null;
        }
        String trimmed = sourceLabel.trim();
        if (trimmed.length() > 300) {
            validation("sourceLabel must not exceed 300 characters");
        }
        return trimmed;
    }

    private String validateContent(String content) {
        splitter.split(content);
        return content.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String validateWithdrawReason(String reason) {
        if (reason == null || reason.isBlank()) {
            validation("Withdraw reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 500) {
            validation("Withdraw reason must not exceed 500 characters");
        }
        return trimmed;
    }

    private static void validation(String message) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private record VersionRow(
        long id,
        long materialId,
        int version,
        long knowledgeId,
        String sourceLabel,
        String content,
        PublicationStatus status
    ) {}
}
