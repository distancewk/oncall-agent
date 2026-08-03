package org.example.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.net.URI;

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
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            requireNonBlank(errors, "app.security.admin-token");
        }
        requireOneNonBlank(errors, "app.security.webhook-signing-secret",
                "app.security.webhook-signing-secrets");
        requireNonBlank(errors, "app.security.session-signing-secret");
        requireTrue(errors, "app.security.webhook-replay-require-redis");
        rejectSameSecret(errors, "app.security.api-token", "app.security.session-signing-secret");
        rejectSameSecret(errors, "app.security.webhook-signing-secret", "app.security.api-token");
        rejectSameSecret(errors, "app.security.webhook-signing-secret", "app.security.session-signing-secret");
        rejectListSecretReuse(errors, "app.security.webhook-signing-secrets", "app.security.api-token");
        rejectListSecretReuse(errors, "app.security.webhook-signing-secrets",
                "app.security.session-signing-secret");
        validateMachineTokens(errors);
        validateOidc(errors);
        validateCors(errors);
        validateTraceExport(errors);
        requireFalse(errors, "prometheus.mock-enabled");
        requireFalse(errors, "cls.mock-enabled");
        requireFalse(errors, "app.alerts.simulate-enabled");
        if (environment.getProperty("app.dependency-probes.cls-native-signing-enabled", Boolean.class, false)) {
            requireNonBlank(errors, "app.dependency-probes.cls-secret-id");
            requireNonBlank(errors, "app.dependency-probes.cls-secret-key");
            requireNonBlank(errors, "app.dependency-probes.cls-region");
            requireNonBlank(errors, "app.dependency-probes.cls-service");
            requireNonBlank(errors, "app.dependency-probes.cls-probe-topic-id");
        }

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

    private void requireOneNonBlank(List<String> errors, String... propertyNames) {
        for (String propertyName : propertyNames) {
            String value = environment.getProperty(propertyName, "");
            if (hasUsableValue(propertyName, value)) {
                return;
            }
        }
        errors.add(String.join(" 或 ", propertyNames) + " 至少配置一项");
    }

    private boolean hasUsableValue(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!propertyName.endsWith("-secrets")) {
            return true;
        }
        return Arrays.stream(value.split(","))
                .anyMatch(candidate -> !candidate.isBlank());
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

    private void validateTraceExport(List<String> errors) {
        if (!environment.getProperty("app.trace-export.enabled", Boolean.class, false)) {
            return;
        }
        requireNonBlank(errors, "app.trace-export.endpoint");
        validateTraceEndpoint(errors);
        String probability = environment.getProperty(
                "app.trace-export.sampling-probability", "0.1");
        try {
            double value = Double.parseDouble(probability);
            if (value < 0.0d || value > 1.0d) {
                errors.add("app.trace-export.sampling-probability 必须在 0 到 1 之间");
            }
        } catch (NumberFormatException e) {
            errors.add("app.trace-export.sampling-probability 必须是数字");
        }
        requirePositive(errors, "app.trace-export.timeout-millis");
        requirePositive(errors, "app.trace-export.max-pending");
    }

    private void validateMachineTokens(List<String> errors) {
        if (!environment.getProperty("app.security.machine-token-enabled", Boolean.class, false)) {
            return;
        }
        requireOneNonBlank(errors, "app.security.machine-token-signing-secret",
                "app.security.machine-token-signing-secrets");
        requireTrue(errors, "app.security.machine-token-require-redis");
        rejectSameSecret(errors, "app.security.machine-token-signing-secret", "app.security.api-token");
        rejectSameSecret(errors, "app.security.machine-token-signing-secret",
                "app.security.session-signing-secret");
        rejectSameSecret(errors, "app.security.machine-token-signing-secret",
                "app.security.webhook-signing-secret");
        rejectListSecretReuse(errors, "app.security.machine-token-signing-secrets",
                "app.security.api-token");
        rejectListSecretReuse(errors, "app.security.machine-token-signing-secrets",
                "app.security.session-signing-secret");
        rejectListSecretReuse(errors, "app.security.machine-token-signing-secrets",
                "app.security.webhook-signing-secret");
        requirePositive(errors, "app.security.machine-token-ttl-seconds", "300");
        requirePositive(errors, "app.security.machine-token-clock-skew-seconds", "30");
    }

    private void validateOidc(List<String> errors) {
        if (!environment.getProperty("app.security.oidc.enabled", Boolean.class, false)) {
            return;
        }
        requireNonBlank(errors, "app.security.oidc.issuer-uri");
        requireNonBlank(errors, "app.security.oidc.client-id");
        requireNonBlank(errors, "app.security.oidc.client-secret");
        requireNonBlank(errors, "app.security.oidc.redirect-uri");
        requireNonBlank(errors, "app.security.oidc.registration-id");
        requireNonBlank(errors, "app.security.oidc.scopes");
        requireNonBlank(errors, "app.security.oidc.tenant-claim");
    }

    private void validateTraceEndpoint(List<String> errors) {
        String endpoint = environment.getProperty("app.trace-export.endpoint", "");
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(endpoint.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                errors.add("app.trace-export.endpoint 必须是带主机名的 HTTP(S) URL");
            }
        } catch (IllegalArgumentException e) {
            errors.add("app.trace-export.endpoint 必须是合法的 HTTP(S) URL");
        }
    }

    private void requirePositive(List<String> errors, String propertyName) {
        requirePositive(errors, propertyName, "");
    }

    private void requirePositive(List<String> errors, String propertyName, String defaultValue) {
        String value = environment.getProperty(propertyName, defaultValue);
        try {
            if (Long.parseLong(value) <= 0L) {
                errors.add(propertyName + " 必须为正数");
            }
        } catch (NumberFormatException e) {
            errors.add(propertyName + " 必须为正数");
        }
    }

    private void rejectSameSecret(List<String> errors, String first, String second) {
        String firstValue = environment.getProperty(first, "");
        String secondValue = environment.getProperty(second, "");
        if (!firstValue.isBlank() && firstValue.equals(secondValue)) {
            errors.add(first + " 与 " + second + " 必须使用不同 Secret");
        }
    }

    private void rejectListSecretReuse(List<String> errors, String listProperty, String otherProperty) {
        String configured = environment.getProperty(listProperty, "");
        String other = environment.getProperty(otherProperty, "");
        if (configured == null || configured.isBlank() || other == null || other.isBlank()) {
            return;
        }
        for (String candidate : configured.split(",")) {
            if (candidate.trim().equals(other)) {
                errors.add(listProperty + " 中的 Secret 不能与 " + otherProperty + " 相同");
                return;
            }
        }
    }
}
