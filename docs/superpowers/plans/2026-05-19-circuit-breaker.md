# Circuit Breaker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add dependency-level circuit breakers and make diagnosis reports refuse unsupported facts when evidence is missing or a dependency is open.

**Architecture:** Use Resilience4j CircuitBreaker behind a small local `DependencyGuard` service. Wrap concrete dependency calls first, record breaker-open failures as `DiagnosisEvidence`, and harden `DiagnosisReportService` so failed evidence cannot support factual conclusions.

**Tech Stack:** Spring Boot 3.2, Java 17, Resilience4j CircuitBreaker, JUnit 5, Mockito, existing file-backed Incident/DiagnosisRun model.

---

## File Structure

- Modify `pom.xml`: add Resilience4j dependency/version.
- Create `src/main/java/org/example/config/AppResilienceProperties.java`: bind `app.resilience.*`.
- Create `src/main/java/org/example/service/DependencyGuard.java`: execute guarded dependency calls and expose state snapshots.
- Create `src/main/java/org/example/dto/DependencyHealthSnapshot.java`: API DTO for dependency states.
- Create `src/main/java/org/example/exception/DependencyUnavailableException.java`: explicit breaker/open dependency error.
- Modify `src/main/java/org/example/dto/DiagnosisEvidence.java`: add `errorCode`.
- Modify `src/main/java/org/example/service/DiagnosisEvidenceRecorder.java`: preserve `errorCode` from failed tool JSON and exceptions.
- Modify `src/main/java/org/example/service/DiagnosisReportService.java`: require successful evidence for factual claims.
- Modify `src/main/java/org/example/agent/tool/QueryMetricsTools.java`: guard real Prometheus calls.
- Modify `src/main/java/org/example/agent/tool/QueryLogsTools.java`: guard CLS/log calls.
- Modify `src/main/java/org/example/service/VectorSearchService.java`: guard Milvus search and return empty degraded results.
- Modify `src/main/java/org/example/service/VectorEmbeddingService.java`: guard DashScope embedding calls.
- Create `src/main/java/org/example/controller/SystemDependencyController.java`: expose `/api/system/dependencies`.
- Modify `src/main/resources/application.yml`: add default resilience config.
- Modify `src/main/resources/static/app.js`: render dependency/evidence breaker status.
- Modify or add tests in `src/test/java/org/example/**`.

## Task 1: Core Resilience Infrastructure

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/org/example/config/AppResilienceProperties.java`
- Create: `src/main/java/org/example/dto/DependencyHealthSnapshot.java`
- Create: `src/main/java/org/example/exception/DependencyUnavailableException.java`
- Create: `src/main/java/org/example/service/DependencyGuard.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/org/example/service/DependencyGuardTest.java`

- [ ] **Step 1: Add failing tests for closed, open, and disabled guard behavior**

Create `src/test/java/org/example/service/DependencyGuardTest.java`:

```java
package org.example.service;

import org.example.config.AppResilienceProperties;
import org.example.dto.DependencyHealthSnapshot;
import org.example.exception.DependencyUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyGuardTest {

    @Test
    void execute_shouldOpenCircuitAfterConfiguredFailuresAndUseFallback() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(2);
        properties.getDefaultConfig().setFailureRateThreshold(50);
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
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run:

```bash
mvn -Dtest=DependencyGuardTest test
```

Expected: compilation fails because `AppResilienceProperties`, `DependencyGuard`, `DependencyHealthSnapshot`, and `DependencyUnavailableException` do not exist.

- [ ] **Step 3: Add Resilience4j dependency**

Modify `pom.xml` properties:

```xml
<resilience4j.version>2.2.0</resilience4j.version>
```

Add dependency:

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>${resilience4j.version}</version>
</dependency>
```

- [ ] **Step 4: Add resilience configuration properties**

Create `src/main/java/org/example/config/AppResilienceProperties.java`:

```java
package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.resilience")
public class AppResilienceProperties {

    private boolean enabled = true;
    private InstanceConfig defaultConfig = new InstanceConfig();
    private Map<String, InstanceConfig> instances = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public InstanceConfig getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(InstanceConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Map<String, InstanceConfig> getInstances() {
        return instances;
    }

    public void setInstances(Map<String, InstanceConfig> instances) {
        this.instances = instances;
    }

    public InstanceConfig configFor(String name) {
        InstanceConfig merged = new InstanceConfig();
        merged.copyFrom(defaultConfig);
        InstanceConfig specific = instances == null ? null : instances.get(name);
        if (specific != null) {
            merged.copyFrom(specific);
        }
        return merged;
    }

    public static class InstanceConfig {
        private Float failureRateThreshold = 50.0f;
        private Float slowCallRateThreshold = 50.0f;
        private Duration slowCallDuration = Duration.ofSeconds(5);
        private Integer minimumCalls = 5;
        private Integer permittedHalfOpenCalls = 2;
        private Duration openDuration = Duration.ofSeconds(30);

        public void copyFrom(InstanceConfig other) {
            if (other == null) {
                return;
            }
            if (other.failureRateThreshold != null) this.failureRateThreshold = other.failureRateThreshold;
            if (other.slowCallRateThreshold != null) this.slowCallRateThreshold = other.slowCallRateThreshold;
            if (other.slowCallDuration != null) this.slowCallDuration = other.slowCallDuration;
            if (other.minimumCalls != null) this.minimumCalls = other.minimumCalls;
            if (other.permittedHalfOpenCalls != null) this.permittedHalfOpenCalls = other.permittedHalfOpenCalls;
            if (other.openDuration != null) this.openDuration = other.openDuration;
        }

        public Float getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(Float failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }
        public Float getSlowCallRateThreshold() { return slowCallRateThreshold; }
        public void setSlowCallRateThreshold(Float slowCallRateThreshold) { this.slowCallRateThreshold = slowCallRateThreshold; }
        public Duration getSlowCallDuration() { return slowCallDuration; }
        public void setSlowCallDuration(Duration slowCallDuration) { this.slowCallDuration = slowCallDuration; }
        public Integer getMinimumCalls() { return minimumCalls; }
        public void setMinimumCalls(Integer minimumCalls) { this.minimumCalls = minimumCalls; }
        public Integer getPermittedHalfOpenCalls() { return permittedHalfOpenCalls; }
        public void setPermittedHalfOpenCalls(Integer permittedHalfOpenCalls) { this.permittedHalfOpenCalls = permittedHalfOpenCalls; }
        public Duration getOpenDuration() { return openDuration; }
        public void setOpenDuration(Duration openDuration) { this.openDuration = openDuration; }
    }
}
```

- [ ] **Step 5: Add DTO and exception**

Create `src/main/java/org/example/dto/DependencyHealthSnapshot.java`:

```java
package org.example.dto;

public class DependencyHealthSnapshot {
    private String name;
    private String state;
    private float failureRate;
    private float slowCallRate;
    private int bufferedCalls;
    private String lastError;
    private Long openedAt;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public float getFailureRate() { return failureRate; }
    public void setFailureRate(float failureRate) { this.failureRate = failureRate; }
    public float getSlowCallRate() { return slowCallRate; }
    public void setSlowCallRate(float slowCallRate) { this.slowCallRate = slowCallRate; }
    public int getBufferedCalls() { return bufferedCalls; }
    public void setBufferedCalls(int bufferedCalls) { this.bufferedCalls = bufferedCalls; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Long getOpenedAt() { return openedAt; }
    public void setOpenedAt(Long openedAt) { this.openedAt = openedAt; }
}
```

Create `src/main/java/org/example/exception/DependencyUnavailableException.java`:

```java
package org.example.exception;

public class DependencyUnavailableException extends RuntimeException {

    private final String dependency;
    private final String operation;
    private final String errorCode;

    public DependencyUnavailableException(String dependency, String operation, String errorCode, Throwable cause) {
        super("依赖不可用: " + dependency + "/" + operation + " (" + errorCode + ")", cause);
        this.dependency = dependency;
        this.operation = operation;
        this.errorCode = errorCode;
    }

    public String getDependency() { return dependency; }
    public String getOperation() { return operation; }
    public String getErrorCode() { return errorCode; }
}
```

- [ ] **Step 6: Implement `DependencyGuard`**

Create `src/main/java/org/example/service/DependencyGuard.java`:

```java
package org.example.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.example.config.AppResilienceProperties;
import org.example.dto.DependencyHealthSnapshot;
import org.example.exception.DependencyUnavailableException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class DependencyGuard {

    private final AppResilienceProperties properties;
    private final CircuitBreakerRegistry registry;
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
    private final Map<String, Long> openedAt = new ConcurrentHashMap<>();

    public DependencyGuard(AppResilienceProperties properties) {
        this.properties = properties;
        this.registry = CircuitBreakerRegistry.ofDefaults();
    }

    public <T> T execute(String dependency, String operation, Supplier<T> call, Function<Throwable, T> fallback) {
        CircuitBreaker breaker = breaker(dependency);
        if (!properties.isEnabled()) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                rememberFailure(dependency, e);
                return fallback.apply(e);
            }
        }

        try {
            return breaker.executeSupplier(call);
        } catch (CallNotPermittedException e) {
            DependencyUnavailableException unavailable =
                    new DependencyUnavailableException(dependency, operation, "CIRCUIT_OPEN", e);
            rememberFailure(dependency, unavailable);
            return fallback.apply(unavailable);
        } catch (RuntimeException e) {
            rememberFailure(dependency, e);
            return fallback.apply(e);
        }
    }

    public List<DependencyHealthSnapshot> snapshot() {
        return registry.getAllCircuitBreakers()
                .stream()
                .map(this::toSnapshot)
                .sorted(Comparator.comparing(DependencyHealthSnapshot::getName))
                .toList();
    }

    private CircuitBreaker breaker(String dependency) {
        return registry.circuitBreaker(dependency, () -> buildConfig(properties.configFor(dependency)));
    }

    private CircuitBreakerConfig buildConfig(AppResilienceProperties.InstanceConfig config) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(config.getFailureRateThreshold())
                .slowCallRateThreshold(config.getSlowCallRateThreshold())
                .slowCallDurationThreshold(config.getSlowCallDuration())
                .minimumNumberOfCalls(config.getMinimumCalls())
                .permittedNumberOfCallsInHalfOpenState(config.getPermittedHalfOpenCalls())
                .waitDurationInOpenState(config.getOpenDuration())
                .build();
    }

    private DependencyHealthSnapshot toSnapshot(CircuitBreaker breaker) {
        CircuitBreaker.Metrics metrics = breaker.getMetrics();
        DependencyHealthSnapshot snapshot = new DependencyHealthSnapshot();
        snapshot.setName(breaker.getName());
        snapshot.setState(properties.isEnabled() ? breaker.getState().name() : "DISABLED");
        snapshot.setFailureRate(sanitizeRate(metrics.getFailureRate()));
        snapshot.setSlowCallRate(sanitizeRate(metrics.getSlowCallRate()));
        snapshot.setBufferedCalls(metrics.getNumberOfBufferedCalls());
        snapshot.setLastError(lastErrors.get(breaker.getName()));
        snapshot.setOpenedAt(openedAt.get(breaker.getName()));
        return snapshot;
    }

    private float sanitizeRate(float rate) {
        return rate < 0 ? 0 : rate;
    }

    private void rememberFailure(String dependency, Throwable error) {
        lastErrors.put(dependency, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        CircuitBreaker breaker = registry.find(dependency).orElse(null);
        if (breaker != null && breaker.getState() == CircuitBreaker.State.OPEN) {
            openedAt.putIfAbsent(dependency, System.currentTimeMillis());
        }
    }
}
```

- [ ] **Step 7: Add YAML defaults**

Modify `src/main/resources/application.yml`:

```yaml
app:
  resilience:
    enabled: ${APP_RESILIENCE_ENABLED:true}
    default-config:
      failure-rate-threshold: ${APP_RESILIENCE_FAILURE_RATE_THRESHOLD:50}
      slow-call-rate-threshold: ${APP_RESILIENCE_SLOW_CALL_RATE_THRESHOLD:50}
      slow-call-duration: ${APP_RESILIENCE_SLOW_CALL_DURATION:5s}
      minimum-calls: ${APP_RESILIENCE_MINIMUM_CALLS:5}
      permitted-half-open-calls: ${APP_RESILIENCE_HALF_OPEN_CALLS:2}
      open-duration: ${APP_RESILIENCE_OPEN_DURATION:30s}
    instances:
      prometheus:
        minimum-calls: 3
        open-duration: 20s
      cls-logs:
        minimum-calls: 3
        open-duration: 30s
      dashscope-chat:
        minimum-calls: 5
        slow-call-duration: 30s
        open-duration: 60s
      dashscope-embedding:
        minimum-calls: 5
        open-duration: 60s
      milvus:
        minimum-calls: 3
        open-duration: 30s
      mcp-tavily:
        minimum-calls: 2
        open-duration: 60s
      mcp-dbhub:
        minimum-calls: 2
        open-duration: 60s
```

- [ ] **Step 8: Run tests**

Run:

```bash
mvn -Dtest=DependencyGuardTest,ConfigurationContractTest test
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/java/org/example/config/AppResilienceProperties.java src/main/java/org/example/dto/DependencyHealthSnapshot.java src/main/java/org/example/exception/DependencyUnavailableException.java src/main/java/org/example/service/DependencyGuard.java src/main/resources/application.yml src/test/java/org/example/service/DependencyGuardTest.java
git commit -m "feat: add dependency circuit breaker guard"
```

## Task 2: Evidence Error Codes and Anti-Fabrication Guard

**Files:**
- Modify: `src/main/java/org/example/dto/DiagnosisEvidence.java`
- Modify: `src/main/java/org/example/service/DiagnosisEvidenceRecorder.java`
- Modify: `src/main/java/org/example/service/DiagnosisReportService.java`
- Test: `src/test/java/org/example/service/DiagnosisEvidenceRecorderTest.java`
- Test: `src/test/java/org/example/service/DiagnosisReportServiceTest.java`

- [ ] **Step 1: Add failing tests for `errorCode` and failed evidence rejection**

Append to `DiagnosisEvidenceRecorderTest`:

```java
@Test
void recordToolCall_shouldCaptureErrorCodeFromFailedJsonResult() throws Exception {
    IncidentService incidentService = mock(IncidentService.class);
    DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper);

    recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
            "queryMetricTrend",
            "{\"metric\":\"cpu_usage\"}",
            "1h",
            () -> "{\"success\":false,\"errorCode\":\"CIRCUIT_OPEN\",\"message\":\"Prometheus 熔断\"}"
    ));

    ArgumentCaptor<DiagnosisEvidence> evidenceCaptor = ArgumentCaptor.forClass(DiagnosisEvidence.class);
    verify(incidentService).addToolEvidence(eq("inc-1"), eq("run-1"), evidenceCaptor.capture());
    DiagnosisEvidence evidence = evidenceCaptor.getValue();

    assertEquals("CIRCUIT_OPEN", evidence.getErrorCode());
    assertFalse(evidence.isSuccess());
}
```

Append to `DiagnosisReportServiceTest`:

```java
@Test
void guardReport_shouldNotAllowFailedMetricEvidenceToSupportResourceClaim() {
    DiagnosisRunRecord run = new DiagnosisRunRecord();
    DiagnosisEvidence failedTrend = DiagnosisEvidence.toolCall(
            "queryMetricTrend",
            "{\"metric\":\"cpu_usage\"}",
            "1h",
            "Prometheus 熔断，未执行指标趋势查询",
            "{\"success\":false,\"errorCode\":\"CIRCUIT_OPEN\"}",
            false,
            "Prometheus 熔断",
            1L);
    failedTrend.setId("ev-open");
    failedTrend.setErrorCode("CIRCUIT_OPEN");
    run.getEvidence().add(failedTrend);

    String guarded = service.guardReport("""
            # 告警分析报告

            ### 根因结论
            CPU 使用率持续上升。[evidence: ev-open]
            """, run);

    assertTrue(guarded.contains("校验状态: 需补证"));
    assertTrue(guarded.contains("资源类结论缺少成功的 queryMetricTrend 趋势 evidence"));
    assertTrue(guarded.contains("CIRCUIT_OPEN"));
}
```

- [ ] **Step 2: Run tests and confirm failure**

Run:

```bash
mvn -Dtest=DiagnosisEvidenceRecorderTest,DiagnosisReportServiceTest test
```

Expected: compilation fails because `DiagnosisEvidence.getErrorCode()` and `setErrorCode()` do not exist, or assertion fails because failed metric evidence is accepted.

- [ ] **Step 3: Add `errorCode` to `DiagnosisEvidence`**

Modify `src/main/java/org/example/dto/DiagnosisEvidence.java`:

```java
private String errorCode;

public String getErrorCode() {
    return errorCode;
}

public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
}
```

Inside `toolCall(...)`, set default:

```java
evidence.setErrorCode(null);
```

- [ ] **Step 4: Extract `errorCode` in `DiagnosisEvidenceRecorder`**

Add helper:

```java
private String extractErrorCode(String rawResult, RuntimeException runtimeFailure) {
    if (runtimeFailure instanceof org.example.exception.DependencyUnavailableException unavailable) {
        return unavailable.getErrorCode();
    }
    if (rawResult == null || rawResult.isBlank()) {
        return null;
    }
    try {
        JsonNode json = objectMapper.readTree(rawResult);
        JsonNode errorCode = json.get("errorCode");
        if (errorCode != null && errorCode.isTextual() && !errorCode.asText().isBlank()) {
            return errorCode.asText();
        }
    } catch (Exception ignored) {
        return null;
    }
    return null;
}
```

After creating the evidence in `recordToolCall(...)`:

```java
evidence.setErrorCode(extractErrorCode(rawResult, runtimeFailure));
```

- [ ] **Step 5: Require successful evidence in `DiagnosisReportService`**

Change `hasCitedTool(...)` to require success:

```java
private boolean hasCitedSuccessfulTool(Set<String> citedIds, Map<String, DiagnosisEvidence> evidenceById, String toolName) {
    return citedIds.stream()
            .map(evidenceById::get)
            .anyMatch(evidence -> evidence != null && evidence.isSuccess() && toolName.equals(evidence.getToolName()));
}
```

Update missing checks:

```java
if (containsResourceClaim(report) && !hasCitedSuccessfulTool(knownCitedIds, evidenceById, "queryMetricTrend")) {
    missing.add("资源类结论缺少成功的 queryMetricTrend 趋势 evidence");
}
if (containsLogClaim(report) && !hasCitedSuccessfulTool(knownCitedIds, evidenceById, "queryLogs")) {
    missing.add("日志/异常结论缺少成功的 queryLogs evidence");
}
```

Update available evidence summary:

```java
.map(evidence -> value(evidence.getId()) + "(" + value(evidence.getToolName())
        + "," + (evidence.isSuccess() ? "success" : "failed")
        + (notBlank(evidence.getErrorCode()) ? "," + evidence.getErrorCode() : "") + ")")
```

- [ ] **Step 6: Run tests**

Run:

```bash
mvn -Dtest=DiagnosisEvidenceRecorderTest,DiagnosisReportServiceTest test
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/dto/DiagnosisEvidence.java src/main/java/org/example/service/DiagnosisEvidenceRecorder.java src/main/java/org/example/service/DiagnosisReportService.java src/test/java/org/example/service/DiagnosisEvidenceRecorderTest.java src/test/java/org/example/service/DiagnosisReportServiceTest.java
git commit -m "feat: reject unsupported diagnosis evidence claims"
```

## Task 3: Guard Prometheus and CLS Tool Calls

**Files:**
- Modify: `src/main/java/org/example/agent/tool/QueryMetricsTools.java`
- Modify: `src/main/java/org/example/agent/tool/QueryLogsTools.java`
- Test: `src/test/java/org/example/agent/tool/QueryMetricsToolsTest.java`
- Test: `src/test/java/org/example/agent/tool/QueryLogsToolsTest.java`

- [ ] **Step 1: Add failing Prometheus breaker test**

Append to `QueryMetricsToolsTest`:

```java
@Test
void queryMetricTrend_shouldReturnCircuitOpenErrorWhenPrometheusBreakerIsOpen() throws Exception {
    ReflectionTestUtils.setField(tools, "mockEnabled", false);

    org.example.config.AppResilienceProperties properties = new org.example.config.AppResilienceProperties();
    properties.getDefaultConfig().setMinimumCalls(1);
    properties.getDefaultConfig().setFailureRateThreshold(1);
    org.example.service.DependencyGuard guard = new org.example.service.DependencyGuard(properties);
    ReflectionTestUtils.setField(tools, "dependencyGuard", guard);
    ReflectionTestUtils.setField(tools, "httpClient", mock(okhttp3.OkHttpClient.class));

    String first = tools.queryMetricTrend("cpu_usage", "payment-service", "pod-1", "1h", null);
    JsonNode firstJson = objectMapper.readTree(first);
    assertFalse(firstJson.path("success").asBoolean());

    String second = tools.queryMetricTrend("cpu_usage", "payment-service", "pod-1", "1h", null);
    JsonNode secondJson = objectMapper.readTree(second);
    assertFalse(secondJson.path("success").asBoolean());
    assertEquals("CIRCUIT_OPEN", secondJson.path("errorCode").asText());
    assertTrue(secondJson.path("message").asText().contains("熔断"));
}
```

- [ ] **Step 2: Add failing CLS breaker test**

Append to `QueryLogsToolsTest`:

```java
@Test
void queryLogs_shouldReturnCircuitOpenErrorWhenClsBreakerIsOpen() throws Exception {
    QueryLogsTools tools = new QueryLogsTools();
    ReflectionTestUtils.setField(tools, "mockEnabled", false);

    org.example.config.AppResilienceProperties properties = new org.example.config.AppResilienceProperties();
    properties.getDefaultConfig().setMinimumCalls(1);
    properties.getDefaultConfig().setFailureRateThreshold(1);
    org.example.service.DependencyGuard guard = new org.example.service.DependencyGuard(properties);
    ReflectionTestUtils.setField(tools, "dependencyGuard", guard);

    String first = tools.queryLogs("ap-guangzhou", "application-logs", "level:ERROR", 10);
    JsonNode firstJson = new ObjectMapper().readTree(first);
    assertFalse(firstJson.path("success").asBoolean());

    String second = tools.queryLogs("ap-guangzhou", "application-logs", "level:ERROR", 10);
    JsonNode secondJson = new ObjectMapper().readTree(second);
    assertFalse(secondJson.path("success").asBoolean());
    assertEquals("CIRCUIT_OPEN", secondJson.path("errorCode").asText());
}
```

- [ ] **Step 3: Run tests and confirm failure**

Run:

```bash
mvn -Dtest=QueryMetricsToolsTest,QueryLogsToolsTest test
```

Expected: fails because tools do not use `DependencyGuard` and error JSON lacks `errorCode`.

- [ ] **Step 4: Wire `DependencyGuard` into `QueryMetricsTools`**

Add field:

```java
@Autowired(required = false)
private DependencyGuard dependencyGuard;
```

Add helper:

```java
private <T> T guardedPrometheus(String operation, java.util.function.Supplier<T> call, java.util.function.Function<Throwable, T> fallback) {
    if (dependencyGuard == null || mockEnabled) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return fallback.apply(e);
        }
    }
    return dependencyGuard.execute("prometheus", operation, call, fallback);
}

private String errorCode(Throwable error) {
    if (error instanceof org.example.exception.DependencyUnavailableException unavailable) {
        return unavailable.getErrorCode();
    }
    return error == null ? "DEPENDENCY_ERROR" : "DEPENDENCY_ERROR";
}
```

Change real calls:

```java
Throwable[] failure = new Throwable[1];
PrometheusAlertsResult result = guardedPrometheus("queryPrometheusAlerts",
        () -> {
            try {
                return fetchPrometheusAlerts();
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        },
        error -> {
            failure[0] = error;
            return null;
        });
if (result == null) {
    return buildErrorResponse("Prometheus 熔断或查询失败，活动告警证据缺失",
            failure[0] == null ? "unknown" : failure[0].getMessage(),
            errorCode(failure[0]));
}
```

For trends:

```java
Throwable[] failure = new Throwable[1];
List<MetricPoint> points = mockEnabled
        ? buildMockTrendPoints(metric, window, step)
        : guardedPrometheus("queryMetricTrend",
                () -> {
                    try {
                        return fetchPrometheusTrend(query, window, step);
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                },
                error -> {
                    failure[0] = error;
                    return null;
                });
if (points == null) {
    return buildMetricTrendErrorResponse(metric, window, query,
            "Prometheus 熔断或查询失败，指标趋势证据缺失",
            errorCode(failure[0]));
}
```

Use a local `Throwable[] failure = new Throwable[1]` if the fallback needs to expose the captured error after `execute`.

- [ ] **Step 5: Add `errorCode` to QueryMetrics output models**

Add to both `PrometheusAlertsOutput` and `MetricTrendOutput`:

```java
@JsonProperty("errorCode")
private String errorCode;
```

Add overloads:

```java
private String buildErrorResponse(String message, String error, String errorCode) { ... }
private String buildMetricTrendErrorResponse(String metric, String window, String query, String error, String errorCode) { ... }
```

Keep existing overloads delegating with `null`.

- [ ] **Step 6: Wire `DependencyGuard` into `QueryLogsTools`**

Add field:

```java
@Autowired(required = false)
private DependencyGuard dependencyGuard;
```

For real mode in `doQueryLogs(...)`:

```java
if (!mockEnabled) {
    Throwable[] failure = new Throwable[1];
    String guarded = dependencyGuard == null
            ? buildErrorResponse("CLS 真实查询尚未实现，请启用 mock 模式进行测试")
            : dependencyGuard.execute("cls-logs", "queryLogs",
                    () -> {
                        throw new IllegalStateException("CLS 真实查询尚未实现，请启用 mock 模式进行测试");
                    },
                    error -> {
                        failure[0] = error;
                        String code = error instanceof org.example.exception.DependencyUnavailableException unavailable
                                ? unavailable.getErrorCode()
                                : "DEPENDENCY_ERROR";
                        return buildErrorResponse("CLS 熔断或查询失败，日志证据缺失", error.getMessage(), code);
                    });
    return guarded;
}
```

Add `errorCode` to `QueryLogsOutput` and error response.

- [ ] **Step 7: Run tests**

Run:

```bash
mvn -Dtest=QueryMetricsToolsTest,QueryLogsToolsTest,DiagnosisEvidenceRecorderTest test
```

Expected: pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/example/agent/tool/QueryMetricsTools.java src/main/java/org/example/agent/tool/QueryLogsTools.java src/test/java/org/example/agent/tool/QueryMetricsToolsTest.java src/test/java/org/example/agent/tool/QueryLogsToolsTest.java
git commit -m "feat: guard metrics and logs dependencies"
```

## Task 4: Guard Milvus and Embedding Paths

**Files:**
- Modify: `src/main/java/org/example/service/VectorSearchService.java`
- Modify: `src/main/java/org/example/service/VectorEmbeddingService.java`
- Test: `src/test/java/org/example/service/VectorSearchServiceTest.java`
- Test: `src/test/java/org/example/service/VectorEmbeddingServiceTest.java`

- [ ] **Step 1: Add failing Milvus degradation test**

Append to `VectorSearchServiceTest`:

```java
@Test
void searchIncidentCases_shouldReturnEmptyResultsWhenMilvusCircuitIsOpen() {
    when(embeddingService.generateQueryVector("cpu incident")).thenReturn(List.of(0.1f, 0.2f));
    TreeMap<Long, Float> sparseVector = new TreeMap<>();
    sparseVector.put(1L, 0.5f);
    when(embeddingService.generateSparseVector("cpu incident")).thenReturn(sparseVector);

    org.example.config.AppResilienceProperties properties = new org.example.config.AppResilienceProperties();
    properties.getDefaultConfig().setMinimumCalls(1);
    properties.getDefaultConfig().setFailureRateThreshold(1);
    DependencyGuard guard = new DependencyGuard(properties);
    ReflectionTestUtils.setField(vectorSearchService, "dependencyGuard", guard);

    when(milvusClient.hybridSearch(any(HybridSearchParam.class)))
            .thenReturn(R.failed(R.Status.UnexpectedError, "milvus down"));

    assertThrows(RuntimeException.class, () -> vectorSearchService.searchIncidentCases("cpu incident", 3));

    List<VectorSearchService.SearchResult> degraded = vectorSearchService.searchIncidentCases("cpu incident", 3);
    assertTrue(degraded.isEmpty());
}
```

- [ ] **Step 2: Add failing embedding breaker test**

Append to `VectorEmbeddingServiceTest`:

```java
@Test
void generateEmbeddings_shouldExposeCircuitOpenWhenEmbeddingDependencyIsOpen() {
    VectorEmbeddingService service = new VectorEmbeddingService();
    ReflectionTestUtils.setField(service, "apiKey", "test-key");
    ReflectionTestUtils.setField(service, "model", "text-embedding-v4");
    ReflectionTestUtils.setField(service, "textEmbedding", mock(com.alibaba.dashscope.embeddings.TextEmbedding.class));

    org.example.config.AppResilienceProperties properties = new org.example.config.AppResilienceProperties();
    properties.getDefaultConfig().setMinimumCalls(1);
    properties.getDefaultConfig().setFailureRateThreshold(1);
    DependencyGuard guard = new DependencyGuard(properties);
    ReflectionTestUtils.setField(service, "dependencyGuard", guard);

    assertThrows(RuntimeException.class, () -> service.generateEmbedding("hello"));
    RuntimeException second = assertThrows(RuntimeException.class, () -> service.generateEmbedding("hello"));
    assertTrue(second.getMessage().contains("CIRCUIT_OPEN"));
}
```

- [ ] **Step 3: Run tests and confirm failure**

Run:

```bash
mvn -Dtest=VectorSearchServiceTest,VectorEmbeddingServiceTest test
```

Expected: fails because `VectorSearchService` and `VectorEmbeddingService` do not use `DependencyGuard`.

- [ ] **Step 4: Guard Milvus search**

Add to `VectorSearchService`:

```java
@Autowired(required = false)
private DependencyGuard dependencyGuard;
```

Wrap the `milvusClient.hybridSearch(...)` call:

```java
R<SearchResults> searchResponse;
if (dependencyGuard == null) {
    searchResponse = milvusClient.hybridSearch(hybridSearchParam);
} else {
    searchResponse = dependencyGuard.execute("milvus", "hybridSearch",
            () -> milvusClient.hybridSearch(hybridSearchParam),
            error -> {
                if (error instanceof org.example.exception.DependencyUnavailableException) {
                    return R.failed(R.Status.UnexpectedError, error.getMessage());
                }
                return R.failed(R.Status.UnexpectedError, error.getMessage());
            });
}
```

In the catch block, degrade only for breaker-open errors:

```java
if (e.getCause() instanceof org.example.exception.DependencyUnavailableException
        || e.getMessage().contains("CIRCUIT_OPEN")
        || e.getMessage().contains("依赖不可用")) {
    logger.warn("{} 检索因 Milvus 熔断降级为空结果", searchLabel, e);
    SearchTrace trace = new SearchTrace();
    trace.setQuery(query);
    trace.setSearchLabel(searchLabel);
    trace.setRequestedTopK(topK);
    trace.setSearchK(Math.max(topK, candidateK));
    trace.setSearchEf(searchEf);
    trace.setFilterExpr(filterExpr);
    return trace;
}
```

- [ ] **Step 5: Guard embedding calls**

Add to `VectorEmbeddingService`:

```java
@Autowired(required = false)
private DependencyGuard dependencyGuard;
```

Wrap single embedding call:

```java
TextEmbeddingResult result = dependencyGuard == null
        ? textEmbedding.call(param)
        : dependencyGuard.execute("dashscope-embedding", "generateEmbedding",
                () -> {
                    try {
                        return textEmbedding.call(param);
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                },
                error -> {
                    throw new RuntimeException("生成向量嵌入失败: "
                            + (error instanceof org.example.exception.DependencyUnavailableException
                            ? "CIRCUIT_OPEN " : "")
                            + error.getMessage(), error);
                });
```

Apply the same pattern to batch `textEmbedding.call(param)`.

- [ ] **Step 6: Run tests**

Run:

```bash
mvn -Dtest=VectorSearchServiceTest,VectorEmbeddingServiceTest test
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/service/VectorSearchService.java src/main/java/org/example/service/VectorEmbeddingService.java src/test/java/org/example/service/VectorSearchServiceTest.java src/test/java/org/example/service/VectorEmbeddingServiceTest.java
git commit -m "feat: guard vector dependencies"
```

## Task 5: Dependency Health API

**Files:**
- Create: `src/main/java/org/example/controller/SystemDependencyController.java`
- Test: `src/test/java/org/example/controller/SystemDependencyControllerTest.java`

- [ ] **Step 1: Add failing controller test**

Create `src/test/java/org/example/controller/SystemDependencyControllerTest.java`:

```java
package org.example.controller;

import org.example.dto.DependencyHealthSnapshot;
import org.example.service.DependencyGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemDependencyController.class)
class SystemDependencyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DependencyGuard dependencyGuard;

    @Test
    void dependencies_shouldReturnDependencySnapshots() throws Exception {
        DependencyHealthSnapshot prometheus = new DependencyHealthSnapshot();
        prometheus.setName("prometheus");
        prometheus.setState("OPEN");
        prometheus.setFailureRate(66.7f);
        prometheus.setLastError("connection refused");

        when(dependencyGuard.snapshot()).thenReturn(List.of(prometheus));

        mockMvc.perform(get("/api/system/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("prometheus"))
                .andExpect(jsonPath("$.data[0].state").value("OPEN"))
                .andExpect(jsonPath("$.data[0].lastError").value("connection refused"));
    }
}
```

- [ ] **Step 2: Run test and confirm failure**

Run:

```bash
mvn -Dtest=SystemDependencyControllerTest test
```

Expected: fails because controller does not exist.

- [ ] **Step 3: Implement controller**

Create `src/main/java/org/example/controller/SystemDependencyController.java`:

```java
package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.dto.DependencyHealthSnapshot;
import org.example.service.DependencyGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system")
public class SystemDependencyController {

    private final DependencyGuard dependencyGuard;

    public SystemDependencyController(DependencyGuard dependencyGuard) {
        this.dependencyGuard = dependencyGuard;
    }

    @GetMapping("/dependencies")
    public ApiResponse<List<DependencyHealthSnapshot>> dependencies() {
        return ApiResponse.success(dependencyGuard.snapshot());
    }
}
```

- [ ] **Step 4: Run controller test**

Run:

```bash
mvn -Dtest=SystemDependencyControllerTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/controller/SystemDependencyController.java src/test/java/org/example/controller/SystemDependencyControllerTest.java
git commit -m "feat: expose dependency health api"
```

## Task 6: Frontend Breaker Visibility

**Files:**
- Modify: `src/main/resources/static/app.js`
- Modify: `src/main/resources/static/style.css`
- Test: `node --check src/main/resources/static/app.js`

- [ ] **Step 1: Add dependency state client methods**

In `app.js`, add state:

```javascript
this.dependencies = [];
```

Add method:

```javascript
async loadDependencies() {
    try {
        const response = await fetch('/api/system/dependencies');
        const data = await response.json();
        this.dependencies = data.data || [];
    } catch (error) {
        this.dependencies = [];
        console.warn('Failed to load dependency states', error);
    }
}
```

- [ ] **Step 2: Render breaker state in system/knowledge status area**

Add helper:

```javascript
renderDependencyStatus() {
    if (!this.dependencies || this.dependencies.length === 0) {
        return '<div class="dependency-status-empty">暂无依赖状态</div>';
    }
    return this.dependencies.map(item => {
        const state = item.state || 'UNKNOWN';
        const cls = state.toLowerCase().replace(/_/g, '-');
        return `
            <div class="dependency-status-item ${cls}">
                <span class="dependency-status-name">${this.escapeHtml(item.name || '-')}</span>
                <span class="dependency-status-state">${this.escapeHtml(state)}</span>
                ${item.lastError ? `<span class="dependency-status-error">${this.escapeHtml(this.compactText(item.lastError, 80))}</span>` : ''}
            </div>
        `;
    }).join('');
}
```

Call `await this.loadDependencies()` before rendering the knowledge/system status panel.

- [ ] **Step 3: Highlight breaker-open evidence**

Update `renderDiagnosisEvidenceItem(item)`:

```javascript
const breakerOpen = item.errorCode === 'CIRCUIT_OPEN';
const statusText = breakerOpen ? 'BREAKER OPEN' : (ok ? 'SUCCESS' : 'FAILED');
const statusClass = breakerOpen ? 'breaker-open' : (ok ? 'success' : 'failed');
```

Render `errorCode`:

```javascript
${item.errorCode ? `<div class="diagnosis-evidence-error-code">错误码: ${this.escapeHtml(item.errorCode)}</div>` : ''}
${breakerOpen ? `<div class="diagnosis-evidence-breaker-note">依赖熔断，相关证据缺失；报告不能基于该工具生成事实结论。</div>` : ''}
```

- [ ] **Step 4: Add CSS**

Add to `style.css`:

```css
.dependency-status-item,
.diagnosis-evidence-breaker-note {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 10px 12px;
}

.dependency-status-item.open,
.diagnosis-evidence-status.breaker-open,
.diagnosis-evidence-breaker-note {
  background: #fff4e5;
  color: #9a5b00;
  border-color: #f5c16c;
}

.diagnosis-evidence-error-code {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
```

- [ ] **Step 5: Run frontend syntax check**

Run:

```bash
node --check src/main/resources/static/app.js
```

Expected: no output and exit 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/app.js src/main/resources/static/style.css
git commit -m "feat: show dependency breaker status"
```

## Task 7: End-to-End Verification

**Files:**
- Modify tests if required by previous task outputs.
- No new production files unless verification reveals a bug.

- [ ] **Step 1: Run focused backend test suite**

Run:

```bash
mvn -Dtest=DependencyGuardTest,DiagnosisEvidenceRecorderTest,DiagnosisReportServiceTest,QueryMetricsToolsTest,QueryLogsToolsTest,VectorSearchServiceTest,SystemDependencyControllerTest test
```

Expected: all tests pass.

- [ ] **Step 2: Run frontend syntax check**

Run:

```bash
node --check src/main/resources/static/app.js
```

Expected: no output and exit 0.

- [ ] **Step 3: Run full backend tests**

Run:

```bash
mvn test
```

Expected: build success, all tests pass.

- [ ] **Step 4: Manual local verification**

Start normal dev mode:

```bash
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

Expected:

- App starts at `http://localhost:9900`.
- `GET http://localhost:9900/api/system/dependencies` returns a JSON list.
- Simulated alert still creates an Incident and DiagnosisRun.

Start with real Prometheus disabled/unreachable:

```bash
export SPRING_PROFILES_ACTIVE=default
export PROMETHEUS_MOCK_ENABLED=false
export PROMETHEUS_BASE_URL=http://localhost:19090
mvn spring-boot:run
```

Expected:

- Repeated metric trend calls eventually put `prometheus` in `OPEN`.
- Diagnosis evidence includes `errorCode=CIRCUIT_OPEN`.
- Final report says metric trend evidence is missing and does not claim CPU/memory/error-rate trend facts.

- [ ] **Step 5: Commit verification fixes if any**

If verification required code changes:

```bash
git add <changed-files>
git commit -m "test: verify circuit breaker behavior"
```

If no changes were needed, do not create an empty commit.

## Self-Review

- Spec coverage:
  - Dependency-level breakers: Tasks 1, 3, 4.
  - No fabricated report content: Task 2.
  - Evidence for breaker-open tools: Tasks 2 and 3.
  - Dependency status API: Task 5.
  - Frontend visibility: Task 6.
  - Verification: Task 7.
- Placeholder scan:
  - No placeholder markers or unspecified broad error-handling steps remain.
- Type consistency:
  - `DependencyGuard`, `DependencyHealthSnapshot`, `DependencyUnavailableException`, and `errorCode` are introduced before later tasks use them.
  - The API path is consistently `/api/system/dependencies`.
  - Breaker error code is consistently `CIRCUIT_OPEN`.
