package cn.reviewsystem.recommend;

import java.math.BigDecimal;
import java.util.List;

public final class RecommendationDtos {
 public record Parameters(BigDecimal weaknessWeight,BigDecimal timeWeight,BigDecimal errorWeight,int timeWindowDays,int evidenceWindow,int cooldownHours,int defaultCount,int reviewSlots,int diagnosticSlots,int reviewInitialKnowledge,int newUserKnowledgeLimit,int reviewMinimumN){}
 public record Facts(String pool,BigDecimal priority,BigDecimal weakness,BigDecimal time,BigDecimal error,BigDecimal finalScore,BigDecimal ruleScore,int n,BigDecimal daysSincePractice,int errorNumerator,int errorDenominator,List<String> evidenceIds,String selectionCategory,boolean cooldownRelaxed,String cooldownReason,String ruleVersion){}
 public record Item(String questionId,String questionNo,String knowledgeId,String knowledgeCode,int position,Facts reason,String questionVersionId,String questionType,String origin){}
 public record PlannedKnowledge(String knowledgeId,String knowledgeCode,String pool,BigDecimal priority,int n){}
 public record Shortfall(String pool,int missing,String reason){}
 public record Response(String ruleVersion,boolean updating,Parameters parameters,List<PlannedKnowledge> plannedKnowledge,List<Item> review,List<Item> diagnostic,List<Item> items,List<Shortfall> shortfall){}
 private RecommendationDtos(){}
}
