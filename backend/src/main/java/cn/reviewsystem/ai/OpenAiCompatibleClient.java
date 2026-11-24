package cn.reviewsystem.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final AiProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public OpenAiCompatibleClient(AiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public LlmResponse complete(LlmRequest req, Duration timeout) {
        if (!properties.isEnabled()) {
            throw new AiUnavailableException(AiFailure.DISABLED, "AI is disabled");
        }
        if (!properties.isConfigured()) {
            throw new AiUnavailableException(AiFailure.UNCONFIGURED, "AI configuration is incomplete");
        }
        Duration effectiveTimeout = timeout != null
                ? timeout
                : Duration.ofMillis(Math.max(1000L, properties.getTimeoutMs()));
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        String mode = properties.getJsonMode() == null ? "auto" : properties.getJsonMode().trim().toLowerCase();
        boolean useJsonFormat = req.jsonMode() && !"off".equals(mode);

        AiUnavailableException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeOnce(req, effectiveTimeout, useJsonFormat, "auto".equals(mode));
            } catch (AiUnavailableException ex) {
                lastFailure = ex;
                if (!isRetryable(ex.getFailure()) || attempt == maxAttempts) {
                    throw ex;
                }
                log.warn("AI request attempt {}/{} failed with {}, retrying", attempt, maxAttempts, ex.getFailure());
            }
        }
        throw lastFailure;
    }

    private LlmResponse executeOnce(LlmRequest req, Duration timeout, boolean withJsonFormat, boolean allowJsonFallback) {
        String endpoint = normalizeBaseUrl(properties.getBaseUrl()) + "/chat/completions";
        String body = buildRequestBody(req, withJsonFormat);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new AiUnavailableException(AiFailure.TIMEOUT, "AI request timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException(AiFailure.TIMEOUT, "AI request interrupted", e);
        } catch (IOException e) {
            throw new AiUnavailableException(AiFailure.UNREACHABLE, "AI endpoint unreachable", e);
        }

        int status = response.statusCode();
        if (status == 400 && withJsonFormat && allowJsonFallback
                && response.body() != null && response.body().contains("response_format")) {
            log.info("AI provider rejected response_format, retrying without response_format");
            return executeOnce(req, timeout, false, false);
        }
        if (status < 200 || status >= 300) {
            throw mapHttpFailure(status);
        }

        return parseResponse(response.body());
    }

    private String buildRequestBody(LlmRequest req, boolean withJsonFormat) {
        try {
            ObjectNode root = json.createObjectNode();
            root.put("model", properties.getModel());
            ArrayNode messages = root.putArray("messages");
            if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
                ObjectNode sys = messages.addObject();
                sys.put("role", "system");
                sys.put("content", req.systemPrompt());
            }
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", req.userPrompt());
            root.put("temperature", properties.getTemperature());
            root.put("max_tokens", Math.max(1, properties.getMaxOutputTokens()));
            if (withJsonFormat) {
                ObjectNode rf = root.putObject("response_format");
                rf.put("type", "json_object");
            }
            return json.writeValueAsString(root);
        } catch (Exception e) {
            throw new AiUnavailableException(AiFailure.BAD_REQUEST, "Failed to serialize AI request", e);
        }
    }

    private LlmResponse parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new AiUnavailableException(AiFailure.MALFORMED, "Empty AI response body");
        }
        try {
            JsonNode root = json.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new AiUnavailableException(AiFailure.MALFORMED, "Missing choices in AI response");
            }
            JsonNode message = choices.get(0).get("message");
            if (message == null || !message.isObject()) {
                throw new AiUnavailableException(AiFailure.MALFORMED, "Missing message in AI response");
            }
            // Only read message.content; explicitly ignore reasoning_content.
            JsonNode contentNode = message.get("content");
            if (contentNode == null || !contentNode.isTextual() || contentNode.asText().isBlank()) {
                throw new AiUnavailableException(AiFailure.MALFORMED, "Empty assistant content");
            }
            JsonNode finishNode = choices.get(0).get("finish_reason");
            String finishReason = (finishNode != null && finishNode.isTextual()) ? finishNode.asText() : null;
            JsonNode usageNode = root.path("usage");
            int promptTokens = usageNode.path("prompt_tokens").asInt(0);
            int completionTokens = usageNode.path("completion_tokens").asInt(0);
            int totalTokens = usageNode.path("total_tokens").asInt(promptTokens + completionTokens);
            int reasoningTokens = usageNode.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
            return new LlmResponse(
                    contentNode.asText(),
                    new LlmUsage(promptTokens, completionTokens, totalTokens, reasoningTokens),
                    finishReason);
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUnavailableException(AiFailure.MALFORMED, "Malformed JSON in AI response", e);
        }
    }

    private static AiUnavailableException mapHttpFailure(int status) {
        if (status == 429) {
            return new AiUnavailableException(AiFailure.RATE_LIMITED, "AI rate limited (HTTP 429)");
        }
        if (status == 401 || status == 403) {
            return new AiUnavailableException(AiFailure.AUTH, "AI authentication failed (HTTP " + status + ")");
        }
        if (status >= 500) {
            return new AiUnavailableException(AiFailure.SERVER_ERROR, "AI server error (HTTP " + status + ")");
        }
        if (status == 400) {
            return new AiUnavailableException(AiFailure.BAD_REQUEST, "AI bad request (HTTP 400)");
        }
        return new AiUnavailableException(AiFailure.BAD_REQUEST, "AI HTTP error (" + status + ")");
    }

    private static boolean isRetryable(AiFailure failure) {
        return failure == AiFailure.TIMEOUT
                || failure == AiFailure.RATE_LIMITED
                || failure == AiFailure.SERVER_ERROR;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
