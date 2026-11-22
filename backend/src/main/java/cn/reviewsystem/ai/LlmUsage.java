package cn.reviewsystem.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record LlmUsage(int promptTokens, int completionTokens, int totalTokens, int reasoningTokens) {
    public JsonNode toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("prompt_tokens", promptTokens);
        node.put("completion_tokens", completionTokens);
        node.put("total_tokens", totalTokens);
        node.put("reasoning_tokens", reasoningTokens);
        return node;
    }
}
