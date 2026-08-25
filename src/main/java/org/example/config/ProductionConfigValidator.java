package org.example.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ProductionConfigValidator {

    private final Environment environment;

    public ProductionConfigValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))
                && !environment.getProperty("app.security.enabled", Boolean.class, true)) {
            return;
        }

        List<String> errors = new ArrayList<>();
        requireNonBlank(errors, "spring.ai.dashscope.api-key");
        requireTrue(errors, "app.security.enabled");
        requireNonBlank(errors, "app.security.api-token");
        requireNonBlank(errors, "app.security.webhook-secret");
        validateSessionSigningKey(errors);
        validateWebhookHmac(errors);
        validateCors(errors);
        requireFalse(errors, "prometheus.mock-enabled");
        requireFalse(errors, "cls.mock-enabled");
        requireFalse(errors, "app.alerts.simulate-enabled");

        if (!errors.isEmpty()) {
            throw new IllegalStateException("生产配置校验失败: " + String.join("; ", errors));
        }
    }

    private void requireNonBlank(List<String> errors, String propertyName) {
        String value = environment.getProperty(propertyName, "");
        if (value == null || value.isBlank()) {
            errors.add(propertyName + " 不能为空");
        }
    }

    private void requireTrue(List<String> errors, String propertyName) {
        if (!environment.getProperty(propertyName, Boolean.class, false)) {
            errors.add(propertyName + " 必须为 true");
        }
    }

    private void requireFalse(List<String> errors, String propertyName) {
        if (environment.getProperty(propertyName, Boolean.class, false)) {
            errors.add(propertyName + " 在 prod profile 下必须为 false");
        }
    }

    private void validateSessionSigningKey(List<String> errors) {
        // 会话签名密钥分离只在 prod 下强制，避免本地「真实接入 + 鉴权」场景额外多配一个变量。
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        String sessionSigningKey = environment.getProperty("app.security.session-signing-key", "");
        String apiToken = environment.getProperty("app.security.api-token", "");
        if (sessionSigningKey == null || sessionSigningKey.isBlank()) {
            errors.add("app.security.session-signing-key 在 prod profile 下必须独立设置");
        } else if (sessionSigningKey.equals(apiToken)) {
            errors.add("app.security.session-signing-key 不能与 app.security.api-token 相同");
        }
    }

    private void validateWebhookHmac(List<String> errors) {
        // 仅 prod 强制 HMAC；非 prod 允许共享密钥回退，兼容 Alertmanager 本地调试。
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        if (!environment.getProperty("app.security.webhook-hmac-required", Boolean.class, false)) {
            errors.add("app.security.webhook-hmac-required 在 prod profile 下必须为 true");
        }
    }

    private void validateCors(List<String> errors) {
        String origins = environment.getProperty("app.cors.allowed-origins", "");
        if (origins == null || origins.isBlank()) {
            errors.add("app.cors.allowed-origins 不能为空");
            return;
        }
        String normalized = origins.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("*")) {
            errors.add("app.cors.allowed-origins 在 prod profile 下不能包含 *");
        }
        // 本地开发地址约束只在 prod profile 下生效：非 prod 环境（即使启用了安全校验）允许 localhost/127.0.0.1，
        // 以便本地「真实接入 + 验证鉴权」场景下仍能使用本地前端。
        boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));
        if (isProd && (normalized.contains("localhost") || normalized.contains("127.0.0.1"))) {
            errors.add("app.cors.allowed-origins 在 prod profile 下不能使用本地开发地址");
        }
    }
}
