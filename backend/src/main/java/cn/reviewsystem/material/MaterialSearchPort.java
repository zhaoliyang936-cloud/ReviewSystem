package cn.reviewsystem.material;

import java.util.List;

/**
 * Internal port for retrieving published course material chunks (used by admin preview and M10 AI辅导).
 */
public interface MaterialSearchPort {

    List<MaterialChunkHit> search(Long knowledgeId, String query);

    record MaterialChunkHit(
        long materialVersionId,
        long chunkId,
        String title,
        String sourceLabel,
        String text,
        int hitCount
    ) {}
}
