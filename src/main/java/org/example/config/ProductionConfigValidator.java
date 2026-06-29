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
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }

        List<String> errors = new ArrayList<>();
        requireNonBlank(errors, "spring.ai.dashscope.api-key");
        requireTrue(errors, "app.security.enabled");
        requireNonBlank(errors, "app.security.api-token");
        requireNonBlank(errors, "app.security.webhook-secret");
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
        if (normalized.contains("localhost") || normalized.contains("127.0.0.1")) {
            errors.add("app.cors.allowed-origins 在 prod profile 下不能使用本地开发地址");
        }
    }
}
