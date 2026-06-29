package org.example.service;

import org.example.config.AppResilienceProperties;
import org.example.dto.DependencyHealthSnapshot;
import org.example.exception.DependencyUnavailableException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DependencyGuardTest {

    @Test
    void execute_shouldOpenCircuitAfterConfiguredFailuresAndUseFallback() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(2);
        properties.getDefaultConfig().setFailureRateThreshold(50.0f);
        properties.getDefaultConfig().setOpenDuration(Duration.ofSeconds(30));

        DependencyGuard guard = new DependencyGuard(properties);
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            String result = guard.execute("prometheus", "queryMetricTrend",
                    () -> {
                        calls.incrementAndGet();
                        throw new IllegalStateException("connection refused");
                    },
                    error -> "fallback:" + error.getClass().getSimpleName());
            assertEquals("fallback:IllegalStateException", result);
        }

        String openResult = guard.execute("prometheus", "queryMetricTrend",
                () -> {
                    calls.incrementAndGet();
                    return "should-not-run";
                },
                error -> {
                    assertTrue(error instanceof DependencyUnavailableException);
                    return "fallback:open";
                });

        assertEquals("fallback:open", openResult);
        assertEquals(2, calls.get());
        List<DependencyHealthSnapshot> snapshots = guard.snapshot();
        assertEquals("OPEN", snapshots.get(0).getState());
        assertTrue(snapshots.get(0).getLastError().contains("connection refused"));
    }

    @Test
    void execute_shouldBypassCircuitBreakerWhenDisabled() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.setEnabled(false);
        DependencyGuard guard = new DependencyGuard(properties);

        String result = guard.execute("prometheus", "queryMetricTrend",
                () -> "ok",
                error -> "fallback");

        assertEquals("ok", result);
        assertFalse(guard.snapshot().isEmpty());
        assertEquals("DISABLED", guard.snapshot().get(0).getState());
    }

    @Test
    void execute_shouldRetryConfiguredTransientFailuresBeforeFallback() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(5);
        properties.getDefaultConfig().setRetryMaxAttempts(3);
        properties.getDefaultConfig().setRetryWaitDuration(Duration.ofMillis(1));
        ToolCallAttemptContext attemptContext = new ToolCallAttemptContext();
        DependencyGuard guard = new DependencyGuard(properties, attemptContext);
        AtomicInteger calls = new AtomicInteger();

        String result = guard.execute("prometheus", "queryMetricTrend",
                () -> {
                    int attempt = calls.incrementAndGet();
                    if (attempt < 3) {
                        throw new IllegalStateException("temporary timeout");
                    }
                    return "ok";
                },
                error -> "fallback");

        assertEquals("ok", result);
        assertEquals(3, calls.get());
        assertEquals(3, attemptContext.consumeLastAttempts());
        assertEquals("CLOSED", guard.snapshot().get(0).getState());
    }

    @Test
    void executeChecked_shouldPropagateOriginalCheckedExceptionType() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(5);
        DependencyGuard guard = new DependencyGuard(properties);
        IOException original = new IOException("network reset");

        IOException thrown = assertThrows(IOException.class,
                () -> DependencyGuardExecutor.executeChecked(
                        guard,
                        "prometheus",
                        "query",
                        () -> {
                            throw original;
                        }));

        assertEquals(original, thrown);
    }

    @Test
    void execute_shouldRecordAttemptCountWhenRetriesExhausted() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(5);
        properties.getDefaultConfig().setRetryMaxAttempts(2);
        properties.getDefaultConfig().setRetryWaitDuration(Duration.ofMillis(1));
        ToolCallAttemptContext attemptContext = new ToolCallAttemptContext();
        DependencyGuard guard = new DependencyGuard(properties, attemptContext);
        AtomicInteger calls = new AtomicInteger();

        String result = guard.execute("prometheus", "queryMetricTrend",
                () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("temporary timeout");
                },
                error -> "fallback:" + error.getMessage());

        assertEquals("fallback:temporary timeout", result);
        assertEquals(2, calls.get());
        assertEquals(2, attemptContext.consumeLastAttempts());
    }

    @Test
    void configFor_shouldMergeInstanceOverridesWithoutResettingDefaultConfig() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setSlowCallDuration(Duration.ofSeconds(10));
        properties.getDefaultConfig().setMinimumCalls(7);

        AppResilienceProperties.InstanceConfig prometheus = new AppResilienceProperties.InstanceConfig();
        prometheus.setOpenDuration(Duration.ofSeconds(20));
        properties.setInstances(new LinkedHashMap<>(Map.of("prometheus", prometheus)));

        AppResilienceProperties.InstanceConfig resolved = properties.configFor("prometheus");

        assertEquals(Duration.ofSeconds(10), resolved.getSlowCallDuration());
        assertEquals(7, resolved.getMinimumCalls());
        assertEquals(Duration.ofSeconds(20), resolved.getOpenDuration());
    }

    @Test
    void execute_disabledShouldPropagateCallExceptionsWithoutFallback() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.setEnabled(false);
        DependencyGuard guard = new DependencyGuard(properties);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> guard.execute("prometheus", "queryMetricTrend",
                        () -> {
                            throw new IllegalStateException("connection refused");
                        },
                        error -> {
                            fail("fallback should not run when dependency guard is disabled");
                            return "fallback";
                        }));

        assertEquals("connection refused", thrown.getMessage());
        assertFalse(guard.snapshot().isEmpty());
        assertEquals("DISABLED", guard.snapshot().get(0).getState());
    }

    @Test
    void execute_disabledShouldNotCreateCircuitBreakerBeforeCallingSupplier() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.setEnabled(false);
        properties.getDefaultConfig().setMinimumCalls(0);
        DependencyGuard guard = new DependencyGuard(properties);

        String result = guard.execute("prometheus", "queryMetricTrend",
                () -> "ok",
                error -> {
                    fail("fallback should not run when dependency guard is disabled");
                    return "fallback";
                });

        List<DependencyHealthSnapshot> snapshots = guard.snapshot();
        assertEquals("ok", result);
        assertEquals(1, snapshots.size());
        assertEquals("prometheus", snapshots.get(0).getName());
        assertEquals("DISABLED", snapshots.get(0).getState());
    }

    @Test
    void execute_shouldClearOpenedAtAfterCircuitCloses() throws Exception {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(1);
        properties.getDefaultConfig().setFailureRateThreshold(50.0f);
        properties.getDefaultConfig().setPermittedHalfOpenCalls(1);
        properties.getDefaultConfig().setOpenDuration(Duration.ofMillis(20));
        DependencyGuard guard = new DependencyGuard(properties);

        guard.execute("prometheus", "queryMetricTrend",
                () -> {
                    throw new IllegalStateException("connection refused");
                },
                error -> "fallback");

        DependencyHealthSnapshot open = guard.snapshot().get(0);
        assertEquals("OPEN", open.getState());
        assertTrue(open.getOpenedAt() != null);

        Thread.sleep(40);

        String recovered = guard.execute("prometheus", "queryMetricTrend",
                () -> "ok",
                error -> "fallback");

        DependencyHealthSnapshot closed = guard.snapshot().get(0);
        assertEquals("ok", recovered);
        assertEquals("CLOSED", closed.getState());
        assertEquals(null, closed.getOpenedAt());
    }

    @Test
    void execute_shouldSetOpenedAtWhenCircuitOpensDueToSlowCalls() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(1);
        properties.getDefaultConfig().setSlowCallDuration(Duration.ofMillis(1));
        properties.getDefaultConfig().setSlowCallRateThreshold(50.0f);
        properties.getDefaultConfig().setOpenDuration(Duration.ofSeconds(30));
        DependencyGuard guard = new DependencyGuard(properties);

        String result = guard.execute("prometheus", "queryMetricTrend",
                () -> {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
                    }
                    return "ok";
                },
                error -> "fallback");

        DependencyHealthSnapshot snapshot = guard.snapshot().get(0);
        assertEquals("ok", result);
        assertEquals("OPEN", snapshot.getState());
        assertTrue(snapshot.getOpenedAt() != null);
    }

    @Test
    void execute_shouldRegisterOnlyOneEventListenerForRepeatedDependencyExecutions() {
        AppResilienceProperties properties = new AppResilienceProperties();
        DependencyGuard guard = new DependencyGuard(properties);

        for (int i = 0; i < 3; i++) {
            String result = guard.execute("prometheus", "queryMetricTrend",
                    () -> "ok",
                    error -> "fallback");
            assertEquals("ok", result);
        }

        assertEquals(1, guard.registeredBreakerCount());
    }

    @Test
    void assertAvailable_shouldThrowCircuitOpenAfterRecordedFailures() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(1);
        properties.getDefaultConfig().setFailureRateThreshold(50.0f);
        properties.getDefaultConfig().setOpenDuration(Duration.ofSeconds(30));
        DependencyGuard guard = new DependencyGuard(properties);

        guard.recordFailure("dashscope-chat", "stream", new IllegalStateException("stream failed"));

        DependencyUnavailableException thrown = assertThrows(DependencyUnavailableException.class,
                () -> guard.assertAvailable("dashscope-chat", "stream"));

        assertEquals("dashscope-chat", thrown.getDependency());
        assertEquals("stream", thrown.getOperation());
        assertEquals("CIRCUIT_OPEN", thrown.getErrorCode());
    }

    @Test
    void recordSuccess_shouldCloseHalfOpenCircuitAndClearOpenedAt() throws Exception {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(1);
        properties.getDefaultConfig().setFailureRateThreshold(50.0f);
        properties.getDefaultConfig().setPermittedHalfOpenCalls(1);
        properties.getDefaultConfig().setOpenDuration(Duration.ofMillis(20));
        DependencyGuard guard = new DependencyGuard(properties);

        guard.recordFailure("dashscope-chat", "stream", new IllegalStateException("stream failed"));
        assertEquals("OPEN", guard.snapshot().get(0).getState());

        Thread.sleep(40);
        guard.recordSuccess("dashscope-chat");

        DependencyHealthSnapshot snapshot = guard.snapshot().get(0);
        assertEquals("CLOSED", snapshot.getState());
        assertEquals(null, snapshot.getOpenedAt());
    }
}
