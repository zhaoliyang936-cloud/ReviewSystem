package cn.reviewsystem.question;

import java.util.List;

public record CoverageReport(List<KnowledgeCoverage> knowledgePoints,List<KnowledgeSummary> zeroQuestionKnowledgePoints,List<KnowledgeSummary> unusableOnlyQuestionKnowledgePoints,long missingBindingCount,long incompleteFieldCount,List<IncompleteQuestion> incompleteQuestions) {
 public record KnowledgeCoverage(String knowledgePointId,String code,String name,boolean zeroQuestion,boolean unusableOnlyQuestion,List<TypeCount> counts){}
 public record TypeCount(String type,long manual,long aiGenerated){}
 public record KnowledgeSummary(String knowledgePointId,String code,String name){}
 public record IncompleteQuestion(String questionId,String questionNo,String message){}
}
