package org.example.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionConfigValidatorTest {

    @Test
    void validate_shouldRejectProdWhenRequiredSecretsAreMissing() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.security.api-token", "")
                .withProperty("app.security.webhook-signing-secret", "webhook-secret")
                .withProperty("app.security.session-signing-secret", "session-secret");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("app.security.api-token"));
    }

    @Test
    void validate_shouldRejectProdWhenMockOrSimulationIsEnabled() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("prometheus.mock-enabled", "true");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("prometheus.mock-enabled"));
    }

    @Test
    void validate_shouldAcceptCompleteProdConfiguration() {
        MockEnvironment environment = baseProdEnvironment();

        assertDoesNotThrow(() -> new ProductionConfigValidator(environment).validate());
    }

    @Test
    void validate_shouldAllowMissingAdminTokenOutsideProd() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("spring.profiles.active", "dev")
                .withProperty("app.security.admin-token", "");

        assertDoesNotThrow(() -> new ProductionConfigValidator(environment).validate());
    }

    @Test
    void validate_shouldRejectProdWhenCorsContainsWildcard() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.cors.allowed-origins", "https://ops.example.com,*");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("app.cors.allowed-origins"));
        assertTrue(thrown.getMessage().contains("*"));
    }

    @Test
    void validate_shouldRejectProdWhenCorsContainsLocalhost() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.cors.allowed-origins", "https://ops.example.com,http://127.0.0.1:9900");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("app.cors.allowed-origins"));
        assertTrue(thrown.getMessage().contains("本地开发地址"));
    }

    @Test
    void validate_shouldRejectProdWhenCorsIsBlank() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.cors.allowed-origins", " ");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("app.cors.allowed-origins"));
        assertTrue(thrown.getMessage().contains("不能为空"));
    }

    @Test
    void validate_shouldAcceptRotationListAsTheOnlyWebhookSecretSource() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.security.webhook-signing-secret", "")
                .withProperty("app.security.webhook-signing-secrets", "new-secret,old-secret");

        assertDoesNotThrow(() -> new ProductionConfigValidator(environment).validate());
    }

    @Test
    void validate_shouldRejectRotationSecretReusedAsApiToken() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.security.webhook-signing-secret", "")
                .withProperty("app.security.webhook-signing-secrets", "api-token,old-secret");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("webhook-signing-secrets"));
    }

    @Test
    void validate_shouldRejectSingleWebhookSecretReusedAsApiToken() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.security.webhook-signing-secret", "api-token");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("webhook-signing-secret"));
    }

    @Test
    void validate_shouldRejectRotationListWithoutUsableSecret() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.security.webhook-signing-secret", "")
                .withProperty("app.security.webhook-signing-secrets", ", ,");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("webhook-signing-secrets"));
    }

    @Test
    void validate_shouldRejectEnabledTraceExportWithoutBoundedConfiguration() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.trace-export.enabled", "true")
                .withProperty("app.trace-export.endpoint", "")
                .withProperty("app.trace-export.sampling-probability", "1.1")
                .withProperty("app.trace-export.timeout-millis", "0")
                .withProperty("app.trace-export.max-pending", "0");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("app.trace-export.endpoint"));
        assertTrue(thrown.getMessage().contains("sampling-probability"));
        assertTrue(thrown.getMessage().contains("timeout-millis"));
        assertTrue(thrown.getMessage().contains("max-pending"));
    }

    @Test
    void validate_shouldRejectEnabledMachineTokensWithoutIndependentSecret() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.security.machine-token-enabled", "true")
                .withProperty("app.security.machine-token-require-redis", "true");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("machine-token-signing-secret"));
    }

    @Test
    void validate_shouldAcceptEnabledMachineTokensWithIndependentSecret() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.security.machine-token-enabled", "true")
                .withProperty("app.security.machine-token-require-redis", "true")
                .withProperty("app.security.machine-token-signing-secret", "machine-secret");

        assertDoesNotThrow(() -> new ProductionConfigValidator(environment).validate());
    }

    @Test
    void validate_shouldRejectEnabledOidcWithoutTrustedClientConfiguration() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.security.oidc.enabled", "true");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("app.security.oidc.issuer-uri"));
        assertTrue(thrown.getMessage().contains("app.security.oidc.tenant-claim"));
    }

    @Test
    void validate_shouldAcceptOidcWithTrustedClientConfiguration() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.security.oidc.enabled", "true")
                .withProperty("app.security.oidc.issuer-uri", "https://idp.example.com")
                .withProperty("app.security.oidc.client-id", "client")
                .withProperty("app.security.oidc.client-secret", "secret")
                .withProperty("app.security.oidc.redirect-uri",
                        "https://ops.example.com/login/oauth2/code/enterprise")
                .withProperty("app.security.oidc.registration-id", "enterprise")
                .withProperty("app.security.oidc.scopes", "openid,profile,email")
                .withProperty("app.security.oidc.tenant-claim", "tenant_id");

        assertDoesNotThrow(() -> new ProductionConfigValidator(environment).validate());
    }

    @Test
    void validate_shouldRejectNativeClsWithoutReadOnlyProbeTopic() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.dependency-probes.cls-native-signing-enabled", "true")
                .withProperty("app.dependency-probes.cls-secret-id", "id")
                .withProperty("app.dependency-probes.cls-secret-key", "key")
                .withProperty("app.dependency-probes.cls-region", "ap-guangzhou")
                .withProperty("app.dependency-probes.cls-service", "cls");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("app.dependency-probes.cls-probe-topic-id"));
    }

    @Test
    void validate_shouldRejectEnabledTraceExportWithInvalidEndpoint() {
        MockEnvironment environment = baseProdEnvironment()
                .withProperty("app.trace-export.enabled", "true")
                .withProperty("app.trace-export.endpoint", "collector.invalid/path");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).validate());

        assertTrue(thrown.getMessage().contains("app.trace-export.endpoint"));
    }

    private MockEnvironment baseProdEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.profiles.active", "prod")
                .withProperty("spring.ai.dashscope.api-key", "dashscope-key")
                .withProperty("app.security.enabled", "true")
                .withProperty("app.security.api-token", "api-token")
                .withProperty("app.security.admin-token", "admin-token")
                .withProperty("app.security.webhook-signing-secret", "webhook-secret")
                .withProperty("app.security.session-signing-secret", "session-secret")
                .withProperty("app.security.webhook-replay-require-redis", "true")
                .withProperty("app.cors.allowed-origins", "https://ops.example.com")
                .withProperty("prometheus.mock-enabled", "false")
                .withProperty("cls.mock-enabled", "false")
                .withProperty("app.alerts.simulate-enabled", "false");
    }
}
