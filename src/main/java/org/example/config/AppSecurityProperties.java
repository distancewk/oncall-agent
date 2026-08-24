package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.security")
public class AppSecurityProperties {

    private boolean enabled;
    private String apiToken = "";
    private String webhookSecret = "";
    private String sessionSigningKey = "";
    private boolean webhookHmacRequired;
    private long webhookTimestampToleranceSeconds = 300;
    private long sessionTtlSeconds = 28800;
    private boolean cookieSecure;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getSessionSigningKey() {
        return sessionSigningKey;
    }

    public void setSessionSigningKey(String sessionSigningKey) {
        this.sessionSigningKey = sessionSigningKey;
    }

    public boolean isWebhookHmacRequired() {
        return webhookHmacRequired;
    }

    public void setWebhookHmacRequired(boolean webhookHmacRequired) {
        this.webhookHmacRequired = webhookHmacRequired;
    }

    public long getWebhookTimestampToleranceSeconds() {
        return webhookTimestampToleranceSeconds;
    }

    public void setWebhookTimestampToleranceSeconds(long webhookTimestampToleranceSeconds) {
        this.webhookTimestampToleranceSeconds = webhookTimestampToleranceSeconds;
    }

    /**
     * 会话 Cookie 签名使用的密钥。优先使用独立配置的 session-signing-key，
     * 未配置时回退到 apiToken（保持向后兼容）。生产 profile 由
     * {@code ProductionConfigValidator} 强制二者分离。
     */
    public String effectiveSessionSigningKey() {
        return (sessionSigningKey != null && !sessionSigningKey.isBlank())
                ? sessionSigningKey
                : apiToken;
    }

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }
}
