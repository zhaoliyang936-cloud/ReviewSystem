package cn.reviewsystem.ai;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {
    private static final Logger log = LoggerFactory.getLogger(AiProperties.class);

    private boolean enabled = false;
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    private long timeoutMs = 60000;
    private int maxAttempts = 2;
    private int prepareTimeoutSeconds = 90;
    private long masteryTimeoutMs = 20000;
    private int dailyCallLimit = 200;
    private double duplicateThreshold = 0.85;
    private double temperature = 0.7;
    private int maxOutputTokens = 8192;
    private String jsonMode = "auto";
    private Quota quota = new Quota();

    public static class Quota {
        private int generationDaily = 50;
        private int reviewDaily = 50;
        private int masteryDaily = 30;
        private int tutoringDaily = 20;

        public int getGenerationDaily() { return generationDaily; }
        public void setGenerationDaily(int generationDaily) { this.generationDaily = generationDaily; }
        public int getReviewDaily() { return reviewDaily; }
        public void setReviewDaily(int reviewDaily) { this.reviewDaily = reviewDaily; }
        public int getMasteryDaily() { return masteryDaily; }
        public void setMasteryDaily(int masteryDaily) { this.masteryDaily = masteryDaily; }
        public int getTutoringDaily() { return tutoringDaily; }
        public void setTutoringDaily(int tutoringDaily) { this.tutoringDaily = tutoringDaily; }
    }

    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(model)) {
            throw new IllegalStateException(
                    "app.ai.enabled=true requires app.ai.base-url, app.ai.api-key, and app.ai.model to be non-empty");
        }
        String host = safeHost(baseUrl);
        log.info("AI integration enabled: host={}, model={}", host, model);
    }

    public boolean isConfigured() {
        return enabled && !isBlank(baseUrl) && !isBlank(apiKey) && !isBlank(model);
    }

    private static String safeHost(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "unknown" : host;
        } catch (Exception e) {
            return "invalid";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getPrepareTimeoutSeconds() { return prepareTimeoutSeconds; }
    public void setPrepareTimeoutSeconds(int prepareTimeoutSeconds) { this.prepareTimeoutSeconds = prepareTimeoutSeconds; }
    public long getMasteryTimeoutMs() { return masteryTimeoutMs; }
    public void setMasteryTimeoutMs(long masteryTimeoutMs) { this.masteryTimeoutMs = masteryTimeoutMs; }
    public int getDailyCallLimit() { return dailyCallLimit; }
    public void setDailyCallLimit(int dailyCallLimit) { this.dailyCallLimit = dailyCallLimit; }
    public double getDuplicateThreshold() { return duplicateThreshold; }
    public void setDuplicateThreshold(double duplicateThreshold) { this.duplicateThreshold = duplicateThreshold; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public String getJsonMode() { return jsonMode; }
    public void setJsonMode(String jsonMode) { this.jsonMode = jsonMode; }
    public Quota getQuota() { return quota; }
    public void setQuota(Quota quota) { this.quota = quota; }
}
