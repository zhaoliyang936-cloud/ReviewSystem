package cn.reviewsystem.ai;

public record LlmResponse(String content, LlmUsage usage, String finishReason) {}
