package cn.reviewsystem.question;

import com.fasterxml.jackson.databind.JsonNode;

public record QuestionWriteRequest(String knowledgePointId,String type,JsonNode content,JsonNode answer,JsonNode rubric,String explanation,String sourceRef,String origin,Double maxScore,Integer expectedVersion) {}
