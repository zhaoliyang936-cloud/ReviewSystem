package cn.reviewsystem.material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Deterministic keyword-hit search over PUBLISHED course materials for subject_id=1.
 */
@Service
public class MaterialSearchService implements MaterialSearchPort {

    public static final int MAX_HITS = 3;
    private static final Pattern TOKEN_RUNS = Pattern.compile("[\\u4e00-\\u9fff]+|[a-z0-9]+");

    private final JdbcTemplate db;

    public MaterialSearchService(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public List<MaterialChunkHit> search(Long knowledgeId, String query) {
        boolean hasQuery = query != null && !query.isBlank();
        Set<String> tokens = hasQuery ? tokenize(query) : Set.of();
        if (hasQuery && tokens.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("""
            SELECT c.id AS chunk_id, c.material_version_id, mv.title, mv.source_label, c.text
            FROM rs_material_chunk c
            JOIN rs_material_version mv ON mv.id = c.material_version_id
            WHERE mv.subject_id = 1
              AND mv.publication_status = 'PUBLISHED'
              AND mv.id = (
                  SELECT mv2.id
                  FROM rs_material_version mv2
                  WHERE mv2.material_id = mv.material_id
                    AND mv2.publication_status = 'PUBLISHED'
                  ORDER BY mv2.version DESC
                  LIMIT 1
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM rs_material_version mv3
                  WHERE mv3.material_id = mv.material_id
                    AND mv3.version > mv.version
                    AND mv3.publication_status = 'WITHDRAWN'
              )
            """);
        List<Object> args = new ArrayList<>();
        if (knowledgeId != null) {
            sql.append(" AND mv.knowledge_id = ?");
            args.add(knowledgeId);
        }
        sql.append(" ORDER BY c.id ASC");

        List<Candidate> candidates = db.query(
            sql.toString(),
            (rs, rowNum) -> new Candidate(
                rs.getLong("material_version_id"),
                rs.getLong("chunk_id"),
                rs.getString("title"),
                rs.getString("source_label"),
                rs.getString("text")
            ),
            args.toArray()
        );

        if (!hasQuery) {
            return candidates.stream()
                .limit(MAX_HITS)
                .map(c -> new MaterialChunkHit(c.materialVersionId(), c.chunkId(), c.title(), c.sourceLabel(), c.text(), 0))
                .toList();
        }

        List<MaterialChunkHit> scored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String lowerText = candidate.text().toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String token : tokens) {
                if (lowerText.contains(token)) {
                    hits++;
                }
            }
            if (hits > 0) {
                scored.add(new MaterialChunkHit(
                    candidate.materialVersionId(),
                    candidate.chunkId(),
                    candidate.title(),
                    candidate.sourceLabel(),
                    candidate.text(),
                    hits
                ));
            }
        }

        scored.sort(Comparator.comparingInt(MaterialChunkHit::hitCount).reversed()
            .thenComparingLong(MaterialChunkHit::chunkId));

        if (scored.size() > MAX_HITS) {
            return scored.subList(0, MAX_HITS);
        }
        return scored;
    }

    /**
     * Tokenize query deterministically:
     * - English/digits: lowercase whole words [a-z0-9]+
     * - Chinese: contiguous CJK runs of length >= 2 split into 2-character bigrams
     */
    public static Set<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN_RUNS.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String run = matcher.group();
            if (isCjk(run.charAt(0))) {
                if (run.length() >= 2) {
                    for (int i = 0; i <= run.length() - 2; i++) {
                        tokens.add(run.substring(i, i + 2));
                    }
                }
            } else {
                tokens.add(run);
            }
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    private record Candidate(long materialVersionId, long chunkId, String title, String sourceLabel, String text) {}
}
