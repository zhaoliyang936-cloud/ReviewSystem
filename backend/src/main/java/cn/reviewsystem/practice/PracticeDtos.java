package cn.reviewsystem.practice;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

public final class PracticeDtos {
 private PracticeDtos(){}
 public record CreateRequest(List<String> knowledgeIds,Integer questionCount,String sourceMode,String userId,String subjectId){}
 public record PracticeView(String id,String activityId,String status,int questionCount,int requestedCount,int preparationTimeoutSeconds,JsonNode mixRatio,List<ItemView> items,String attemptType,String variantWrongQuestionId){
  public PracticeView(String id,String activityId,String status,int questionCount,int requestedCount,int preparationTimeoutSeconds,JsonNode mixRatio,List<ItemView> items){
   this(id,activityId,status,questionCount,requestedCount,preparationTimeoutSeconds,mixRatio,items,"ORDINARY",null);
  }
  public PracticeView(String id,String activityId,String status,int questionCount,int requestedCount,JsonNode mixRatio,List<ItemView> items){
   this(id,activityId,status,questionCount,requestedCount,90,mixRatio,items,"ORDINARY",null);
  }
 }
 public record ItemView(String id,int position,String questionId,String questionVersionId,String knowledgeId,String type,double maxScore,JsonNode content,List<QuestionAssetView> questionAssets,JsonNode draft,int draftVersion,List<AssetView> assets){}
 public record QuestionAssetView(String id,int position,String mime,String url){}
 public record AssetView(String id,int position,String mime,long sizeBytes,String url){}
 public record DraftRequest(JsonNode answer,Integer draftVersion,String sourceMode,String userId,String subjectId){}
 public record DraftView(JsonNode answer,int draftVersion){}
 public record SubmitRequest(List<FinalAnswer> answers,String sourceMode,String userId,String subjectId){}
 public record FinalAnswer(String itemId,JsonNode answer,List<String> assetIds){}
 public record ResultView(String id,String activityId,String status,OffsetDateTime submittedAt,List<ResultItem> items){}
 public record ResultItem(String itemId,int position,String type,double maxScore,JsonNode content,JsonNode submittedAnswer,JsonNode correctAnswer,JsonNode rubric,String explanation,JsonNode result,List<AssetView> assets,String wrongQuestionId){
  public ResultItem(String itemId,int position,String type,double maxScore,JsonNode content,JsonNode submittedAnswer,JsonNode correctAnswer,JsonNode rubric,String explanation,JsonNode result,List<AssetView> assets){
   this(itemId,position,type,maxScore,content,submittedAnswer,correctAnswer,rubric,explanation,result,assets,null);
  }
 }
 public record HistoryItem(String id,String activityId,String status,int questionCount,JsonNode mixRatio,OffsetDateTime createdAt,OffsetDateTime submittedAt){}
}
