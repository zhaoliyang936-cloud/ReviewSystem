package cn.reviewsystem.ai;

public record LlmRequest(String systemPrompt, String userPrompt, boolean jsonMode) {
    public LlmRequest(String userPrompt, boolean jsonMode) {
        this(null, userPrompt, jsonMode);
    }
}
