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
                .withProperty("app.security.webhook-secret", "webhook-secret");

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

    private MockEnvironment baseProdEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.profiles.active", "prod")
                .withProperty("spring.ai.dashscope.api-key", "dashscope-key")
                .withProperty("app.security.enabled", "true")
                .withProperty("app.security.api-token", "api-token")
                .withProperty("app.security.webhook-secret", "webhook-secret")
                .withProperty("app.cors.allowed-origins", "https://ops.example.com")
                .withProperty("prometheus.mock-enabled", "false")
                .withProperty("cls.mock-enabled", "false")
                .withProperty("app.alerts.simulate-enabled", "false");
    }
}
