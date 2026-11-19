package cn.reviewsystem.question;

import com.fasterxml.jackson.databind.JsonNode;

public record QuestionListItem(String id,String questionNo,String knowledgePointId,String knowledgePointName,String type,String origin,String status,int version,double maxScore,JsonNode content) {}
