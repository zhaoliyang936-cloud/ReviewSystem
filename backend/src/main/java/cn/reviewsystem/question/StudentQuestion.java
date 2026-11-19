package cn.reviewsystem.question;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record StudentQuestion(String id,String questionNo,String knowledgePointId,String type,int version,double maxScore,JsonNode content,List<AssetRef> assets) {
 public record AssetRef(String id,String url){}
}
