package cn.reviewsystem.question;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AdminQuestionDetail(String id,String questionNo,String knowledgePointId,String type,String origin,String status,String suspensionReason,int version,double maxScore,JsonNode content,JsonNode answer,JsonNode rubric,String explanation,String sourceRef,List<AssetView> assets) {
 public record AssetView(String id,int position,String mime,long sizeBytes,int width,int height,String sha256){}
 public record VersionView(int version,JsonNode content,List<AssetView> assets){}
}
