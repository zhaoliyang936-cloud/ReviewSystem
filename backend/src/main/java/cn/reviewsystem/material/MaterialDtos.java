package cn.reviewsystem.material;

import java.time.Instant;
import java.util.List;

public final class MaterialDtos {

    private MaterialDtos() {}

    public record MaterialWriteRequest(
        String knowledgeId,
        String title,
        String sourceLabel,
        String content,
        Integer expectedVersion
    ) {}

    public record PublishRequest(Integer expectedVersion) {}

    public record WithdrawRequest(String reason, Integer expectedVersion) {}

    public record MaterialListItem(
        String materialId,
        int latestVersion,
        int versionCount,
        String knowledgeId,
        String knowledgeName,
        String title,
        String sourceLabel,
        PublicationStatus publicationStatus,
        Integer publishedVersion,
        Instant approvedAt,
        Instant updatedAt
    ) {}

    public record MaterialVersionView(
        String id,
        int version,
        String knowledgeId,
        String knowledgeName,
        String title,
        String sourceLabel,
        String content,
        PublicationStatus publicationStatus,
        String approvedBy,
        Instant approvedAt,
        String createdBy,
        Instant createdAt
    ) {}

    public record MaterialChunkView(
        String id,
        int position,
        String text
    ) {}

    public record MaterialDetail(
        String materialId,
        int latestVersion,
        int versionCount,
        String knowledgeId,
        String knowledgeName,
        String title,
        String sourceLabel,
        String content,
        PublicationStatus publicationStatus,
        Integer publishedVersion,
        String approvedBy,
        Instant approvedAt,
        Instant createdAt,
        String withdrawReason,
        List<MaterialChunkView> chunks,
        List<MaterialVersionView> versions
    ) {}
}
