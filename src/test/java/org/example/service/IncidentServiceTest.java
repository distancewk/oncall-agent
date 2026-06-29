package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppIncidentProperties;
import org.example.config.AppJobProperties;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IncidentServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void recordAlert_shouldAggregateByFingerprintAndRestoreAfterRestart() {
        IncidentService service = newIncidentService();

        IncidentRecord first = service.recordAlert(alertPayload("fp-cpu-1", "HighCPUUsage", "payment-service"));
        IncidentRecord second = service.recordAlert(alertPayload("fp-cpu-1", "HighCPUUsage", "payment-service"));

        assertEquals(first.getId(), second.getId());
        assertEquals("fingerprint:fp-cpu-1", second.getAggregationKey());
        assertEquals(2, second.getAlertCount());
        assertEquals(2, second.getAlertPayloads().size());

        IncidentService restarted = newIncidentService();
        IncidentRecord restored = restarted.getIncident(first.getId()).orElseThrow();

        assertEquals(first.getId(), restored.getId());
        assertEquals("HighCPUUsage payment-service", restored.getTitle());
        assertEquals("critical", restored.getSeverity());
        assertEquals(2, restored.getAlertCount());
    }

    @Test
    void recordAlert_shouldNormalizeEquivalentCpuAlertNamesAndServiceFallbacks() {
        IncidentService service = newIncidentService();

        IncidentRecord first = service.recordAlert(alertPayloadWithoutFingerprint(
                "CPUUsageHigh", null, "test-service", "test-server-01"));
        IncidentRecord second = service.recordAlert(alertPayloadWithoutFingerprint(
                "HighCPUUsage", "test-service", null, "test-server-01"));

        assertEquals(first.getId(), second.getId());
        assertEquals("cpu_high|test-service|test-server-01|critical", second.getAggregationKey());
        assertEquals(2, second.getAlertCount());
    }

    @Test
    void recordAlert_shouldKeepDifferentInstancesSeparateAfterAlertNameNormalization() {
        IncidentService service = newIncidentService();

        IncidentRecord first = service.recordAlert(alertPayloadWithoutFingerprint(
                "CPUUsageHigh", null, "test-service", "test-server-01"));
        IncidentRecord second = service.recordAlert(alertPayloadWithoutFingerprint(
                "CpuHigh", null, "test-service", "test-server-02"));

        assertFalse(first.getId().equals(second.getId()));
        assertEquals("cpu_high|test-service|test-server-01|critical", first.getAggregationKey());
        assertEquals("cpu_high|test-service|test-server-02|critical", second.getAggregationKey());
    }

    @Test
    void recordAlert_shouldAggregateAtomicallyAcrossServiceInstances() throws Exception {
        AppIncidentProperties properties = incidentProperties();
        IncidentService firstService = newIncidentService(null, properties);
        IncidentService secondService = newIncidentService(null, properties);
        AlertPayload payload = alertPayloadWithoutFingerprint(
                "HighCPUUsage", "payment-service", null, "payment-pod-1");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<IncidentRecord> first = executor.submit(() -> {
                start.await();
                return firstService.recordAlert(payload);
            });
            Future<IncidentRecord> second = executor.submit(() -> {
                start.await();
                return secondService.recordAlert(payload);
            });
            start.countDown();

            IncidentRecord firstResult = first.get(5, TimeUnit.SECONDS);
            IncidentRecord secondResult = second.get(5, TimeUnit.SECONDS);

            assertEquals(firstResult.getId(), secondResult.getId());
            IncidentRecord stored = firstService.getIncident(firstResult.getId()).orElseThrow();
            assertEquals(2, stored.getAlertCount());
            assertEquals(2, stored.getAlertPayloads().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createDiagnosisRun_shouldPersistRunningAndCompletedStateWithEvidence() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-memory-1", "HighMemoryUsage", "order-service"));

        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "告警上下文");
        DiagnosisRunRecord running = service.markRunRunning(incident.getId(), queued.getRunId());
        DiagnosisRunRecord completed = service.completeRun(incident.getId(), queued.getRunId(), "# 告警分析报告");

        assertEquals("QUEUED", queued.getStatus());
        assertEquals("RUNNING", running.getStatus());
        assertEquals("COMPLETED", completed.getStatus());
        assertEquals("# 告警分析报告", completed.getReport());
        assertTrue(completed.getCompletedAt() >= completed.getStartedAt());
        assertEquals(List.of("alert_context"), completed.getEvidence().stream()
                .map(evidence -> evidence.getType())
                .toList());

        IncidentRecord restored = newIncidentService().getIncident(incident.getId()).orElseThrow();
        assertEquals("COMPLETED", restored.getDiagnosisRuns().get(0).getStatus());
    }

    @Test
    void createDiagnosisRunAndEnqueue_shouldPersistDurableJobAndCancelItWithRun() {
        AppIncidentProperties properties = incidentProperties();
        DataSource dataSource = dataSource(properties);
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        IncidentStore store = new IncidentStore(properties, new ObjectMapper(), dataSource, migrator);
        BackgroundJobRepository jobRepository = new BackgroundJobRepository(dataSource, migrator);
        RunningJobRegistry registry = mock(RunningJobRegistry.class);
        AppJobProperties jobProperties = new AppJobProperties();
        IncidentService service = new IncidentService(
                store, new ObjectMapper(), null, properties,
                jobRepository, registry, jobProperties);
        IncidentRecord incident = service.recordAlert(
                alertPayload("fp-job-1", "HighCPUUsage", "payment-service"));

        DiagnosisRunRecord run = service.createDiagnosisRunAndEnqueue(
                incident.getId(), "CPU 告警上下文");

        org.example.dto.BackgroundJobRecord job = jobRepository
                .findByBusinessKey("DIAGNOSIS", run.getRunId())
                .orElseThrow();
        assertEquals("QUEUED", job.getStatus());
        assertEquals(2, job.getMaxAttempts());
        assertTrue(job.getPayload().contains(incident.getId()));

        service.cancelRun(incident.getId(), run.getRunId(), "用户取消");

        assertEquals("CANCELLED", jobRepository.findById(job.getJobId()).orElseThrow().getStatus());
        verify(registry).cancelByBusinessKey("DIAGNOSIS", run.getRunId());
    }

    @Test
    void createDiagnosisRunAndEnqueue_shouldUseRunScopedEvidenceIdsForSameContext() {
        AppIncidentProperties properties = incidentProperties();
        DataSource dataSource = dataSource(properties);
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        IncidentStore store = new IncidentStore(properties, new ObjectMapper(), dataSource, migrator);
        BackgroundJobRepository jobRepository = new BackgroundJobRepository(dataSource, migrator);
        IncidentService service = new IncidentService(
                store, new ObjectMapper(), null, properties,
                jobRepository, null, new AppJobProperties());
        IncidentRecord incident = service.recordAlert(
                alertPayload("fp-job-evidence", "HighCPUUsage", "payment-service"));

        DiagnosisRunRecord first = service.createDiagnosisRunAndEnqueue(
                incident.getId(), "相同告警上下文");
        DiagnosisRunRecord second = service.createDiagnosisRunAndEnqueue(
                incident.getId(), "相同告警上下文");

        String firstEvidenceId = first.getEvidence().get(0).getId();
        String secondEvidenceId = second.getEvidence().get(0).getId();
        assertTrue(firstEvidenceId.startsWith("ev-" + first.getRunId() + "-"));
        assertTrue(secondEvidenceId.startsWith("ev-" + second.getRunId() + "-"));
        assertFalse(firstEvidenceId.equals(secondEvidenceId));
        assertEquals(2, service.getDiagnosisRuns(incident.getId()).orElseThrow().size());
    }

    @Test
    void createDiagnosisRunAndEnqueue_shouldRollbackAllRowsAndIncidentMetadataWhenJobInsertFails() throws Exception {
        AppIncidentProperties properties = incidentProperties();
        DataSource dataSource = dataSource(properties);
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        IncidentStore store = new IncidentStore(properties, new ObjectMapper(), dataSource, migrator);
        BackgroundJobRepository failingJobRepository = new BackgroundJobRepository(dataSource, migrator) {
            @Override
            public org.example.dto.BackgroundJobRecord enqueue(
                    Connection connection,
                    String jobType,
                    String businessKey,
                    String payload,
                    int maxAttempts,
                    long now) throws SQLException {
                throw new SQLException("simulated job insert failure");
            }
        };
        IncidentService service = new IncidentService(
                store, new ObjectMapper(), null, properties,
                failingJobRepository, null, new AppJobProperties());
        IncidentRecord incident = service.recordAlert(
                alertPayload("fp-job-rollback", "HighCPUUsage", "payment-service"));
        IncidentState before = incidentState(dataSource, incident.getId());
        long runsBefore = rowCount(dataSource, "diagnosis_runs");
        long evidenceBefore = rowCount(dataSource, "diagnosis_evidence");
        long jobsBefore = rowCount(dataSource, "background_jobs");

        assertThrows(IllegalStateException.class, () -> service.createDiagnosisRunAndEnqueue(
                incident.getId(), "CPU 告警上下文"));

        assertEquals(runsBefore, rowCount(dataSource, "diagnosis_runs"));
        assertEquals(evidenceBefore, rowCount(dataSource, "diagnosis_evidence"));
        assertEquals(jobsBefore, rowCount(dataSource, "background_jobs"));
        assertEquals(before, incidentState(dataSource, incident.getId()));
    }

    @Test
    void cancelRun_shouldRollbackRunWhenJobCancellationFails() throws Exception {
        AppIncidentProperties properties = incidentProperties();
        DataSource dataSource = dataSource(properties);
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        IncidentStore store = new IncidentStore(properties, new ObjectMapper(), dataSource, migrator);
        BackgroundJobRepository jobRepository = new BackgroundJobRepository(dataSource, migrator);
        IncidentService setupService = new IncidentService(
                store, new ObjectMapper(), null, properties,
                jobRepository, null, new AppJobProperties());
        IncidentRecord incident = setupService.recordAlert(
                alertPayload("fp-cancel-rollback", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord run = setupService.createDiagnosisRunAndEnqueue(
                incident.getId(), "CPU 告警上下文");
        IncidentState before = incidentState(dataSource, incident.getId());

        BackgroundJobRepository failingJobRepository = new BackgroundJobRepository(dataSource, migrator) {
            @Override
            public boolean requestCancel(
                    Connection connection,
                    String jobType,
                    String businessKey,
                    long now) throws SQLException {
                throw new SQLException("simulated job cancellation failure");
            }
        };
        IncidentService failingService = new IncidentService(
                store, new ObjectMapper(), null, properties,
                failingJobRepository, null, new AppJobProperties());

        assertThrows(IllegalStateException.class,
                () -> failingService.cancelRun(incident.getId(), run.getRunId(), "用户取消"));

        DiagnosisRunRecord storedRun = failingService.getDiagnosisRuns(incident.getId())
                .orElseThrow()
                .stream()
                .filter(candidate -> run.getRunId().equals(candidate.getRunId()))
                .findFirst()
                .orElseThrow();
        assertEquals("QUEUED", storedRun.getStatus());
        assertEquals(0, storedRun.getEvidence().stream()
                .filter(item -> "diagnosis_cancelled".equals(item.getType()))
                .count());
        org.example.dto.BackgroundJobRecord storedJob = jobRepository
                .findByBusinessKey("DIAGNOSIS", run.getRunId())
                .orElseThrow();
        assertEquals("QUEUED", storedJob.getStatus());
        assertFalse(storedJob.isCancelRequested());
        assertEquals(before, incidentState(dataSource, incident.getId()));
    }

    @Test
    void incidentStoreSave_shouldExplicitlyRollbackWhenRunSerializationFails() throws Exception {
        AppIncidentProperties properties = incidentProperties();
        RollbackTrackingDataSource dataSource =
                new RollbackTrackingDataSource(dataSource(properties));
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                if (value instanceof List<?>) {
                    throw new JsonProcessingException("simulated run serialization failure") { };
                }
                return super.writeValueAsString(value);
            }
        };
        IncidentStore store = new IncidentStore(properties, failingMapper, dataSource);
        IncidentRecord incident = new IncidentRecord();
        incident.setId("inc-save-rollback");
        incident.setAggregationKey("save-rollback");
        incident.setTitle("before");
        incident.setStatus("OPEN");
        incident.setSeverity("critical");
        incident.setAlertCount(1);
        incident.setCreatedAt(100L);
        incident.setUpdatedAt(200L);
        incident.setLastAlertAt(150L);
        store.save(incident);
        IncidentState before = incidentState(dataSource, incident.getId());
        dataSource.resetRollbackCount();

        incident.setTitle("after");
        incident.setUpdatedAt(300L);
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-save-rollback");
        run.setIncidentId(incident.getId());
        run.setStatus("QUEUED");
        run.setCreatedAt(250L);
        incident.getDiagnosisRuns().add(run);

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> store.save(incident));

        assertEquals("simulated run serialization failure", failure.getCause().getMessage());
        assertEquals(1, dataSource.rollbackCount());
        assertEquals(before, incidentState(dataSource, incident.getId()));
        assertEquals(0L, rowCount(dataSource, "diagnosis_runs"));
    }

    @Test
    void incidentStoreInitialization_shouldRetryAfterLegacyImportFailure() throws Exception {
        AppIncidentProperties properties = incidentProperties();
        Path legacyPath = Path.of(properties.getPath());
        Files.createDirectories(legacyPath.getParent());
        Files.writeString(legacyPath, "not a directory");
        DataSource dataSource = dataSource(properties);
        AtomicInteger migrationAttempts = new AtomicInteger();
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource) {
            @Override
            public synchronized void migrate() {
                migrationAttempts.incrementAndGet();
                super.migrate();
            }
        };
        IncidentStore store = new IncidentStore(
                properties, new ObjectMapper(), dataSource, migrator);

        assertThrows(IllegalStateException.class, store::list);

        Files.delete(legacyPath);
        Files.createDirectories(legacyPath);
        assertTrue(store.list().isEmpty());
        assertEquals(2, migrationAttempts.get());
    }

    @Test
    void failRun_shouldPersistFailureReasonAndEvidence() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-disk-1", "HighDiskUsage", "storage-service"));
        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "磁盘告警上下文");

        DiagnosisRunRecord failed = service.failRun(incident.getId(), queued.getRunId(), "Prometheus 查询失败");

        assertEquals("FAILED", failed.getStatus());
        assertEquals("Prometheus 查询失败", failed.getErrorMessage());
        assertEquals("failure_reason", failed.getEvidence().get(1).getType());
        assertEquals("Prometheus 查询失败", failed.getEvidence().get(1).getContent());
    }

    @Test
    void markStaleRunsFailed_shouldFailTimedOutActiveRunsOnly() {
        AppIncidentProperties properties = incidentProperties();
        properties.setStaleRunTimeoutMillis(500L);
        IncidentService service = newIncidentService(null, properties);
        IncidentRecord incident = service.recordAlert(alertPayload("fp-stale-1", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord stale = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");
        DiagnosisRunRecord running = service.markRunRunning(incident.getId(), stale.getRunId());

        DiagnosisRunRecord completedSource = service.createDiagnosisRun(incident.getId(), "已完成上下文");
        service.completeRun(incident.getId(), completedSource.getRunId(), "# 已完成报告");

        List<DiagnosisRunRecord> failed = service.markStaleRunsFailed(running.getStartedAt() + 501L);

        assertEquals(1, failed.size());
        assertEquals(stale.getRunId(), failed.get(0).getRunId());
        assertEquals("FAILED", failed.get(0).getStatus());
        assertTrue(failed.get(0).getErrorMessage().contains("诊断任务超时"));
        assertEquals("diagnosis_timeout", failed.get(0).getEvidence().get(1).getType());

        IncidentRecord restored = newIncidentService(null, properties).getIncident(incident.getId()).orElseThrow();
        assertEquals("FAILED", restored.getDiagnosisRuns().get(0).getStatus());
        assertEquals("COMPLETED", restored.getDiagnosisRuns().get(1).getStatus());
    }

    @Test
    void markStaleRunsFailed_shouldNotOverwriteRunCompletedAfterSnapshot() {
        AppIncidentProperties properties = incidentProperties();
        properties.setStaleRunTimeoutMillis(500L);
        DataSource sharedDataSource = dataSource(properties);
        IncidentStore setupStore = new IncidentStore(properties, new ObjectMapper(), sharedDataSource);
        IncidentService setupService = new IncidentService(setupStore, new ObjectMapper(), null, properties);
        IncidentRecord incident = setupService.recordAlert(
                alertPayload("fp-stale-completed-race", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord stale = setupService.createDiagnosisRun(incident.getId(), "CPU 告警上下文");
        DiagnosisRunRecord running = setupService.markRunRunning(incident.getId(), stale.getRunId());
        AtomicReference<IncidentService> completingService = new AtomicReference<>();
        AtomicInteger listCalls = new AtomicInteger();
        IncidentStore racingStore = new IncidentStore(properties, new ObjectMapper(), sharedDataSource) {
            @Override
            public List<IncidentRecord> list() {
                List<IncidentRecord> snapshot = super.list();
                if (listCalls.getAndIncrement() == 0) {
                    completingService.get().completeRun(incident.getId(), stale.getRunId(), "# 已完成报告");
                }
                return snapshot;
            }
        };
        completingService.set(new IncidentService(
                new IncidentStore(properties, new ObjectMapper(), sharedDataSource),
                new ObjectMapper(),
                null,
                properties));
        IncidentService racingService = new IncidentService(racingStore, new ObjectMapper(), null, properties);

        List<DiagnosisRunRecord> failed = racingService.markStaleRunsFailed(running.getStartedAt() + 501L);

        assertTrue(failed.isEmpty());
        DiagnosisRunRecord restored = setupService.getDiagnosisRuns(incident.getId())
                .orElseThrow()
                .get(0);
        assertEquals("COMPLETED", restored.getStatus());
        assertEquals("# 已完成报告", restored.getReport());
        assertEquals(0, restored.getEvidence().stream()
                .filter(evidence -> "diagnosis_timeout".equals(evidence.getType()))
                .count());
    }

    @Test
    void cancelRun_shouldMarkActiveRunCancelledAndRemainIdempotent() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cancel-1", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");

        DiagnosisRunRecord cancelled = service.cancelRun(incident.getId(), queued.getRunId(), "用户取消");

        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals("用户取消", cancelled.getErrorMessage());
        assertEquals("diagnosis_cancelled", cancelled.getEvidence().get(1).getType());
        assertEquals("ev-" + queued.getRunId() + "-cancelled",
                cancelled.getEvidence().get(1).getId());
        assertTrue(cancelled.getCompletedAt() >= cancelled.getCreatedAt());

        DiagnosisRunRecord repeated = service.cancelRun(incident.getId(), queued.getRunId(), "再次取消");
        assertEquals("CANCELLED", repeated.getStatus());
        assertEquals(1, repeated.getEvidence().stream()
                .filter(item -> "diagnosis_cancelled".equals(item.getType()))
                .count());
    }

    @Test
    void concurrentCancellation_shouldAppendOneCancellationEvidence() throws Exception {
        AppIncidentProperties properties = incidentProperties();
        IncidentService setupService = newIncidentService(null, properties);
        IncidentRecord incident = setupService.recordAlert(
                alertPayload("fp-cancel-concurrent", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord run = setupService.createDiagnosisRun(incident.getId(), "CPU 告警上下文");
        IncidentService firstService = newIncidentService(null, properties);
        IncidentService secondService = newIncidentService(null, properties);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DiagnosisRunRecord> first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return firstService.cancelRun(incident.getId(), run.getRunId(), "用户取消");
            });
            Future<DiagnosisRunRecord> second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return secondService.cancelRun(incident.getId(), run.getRunId(), "用户取消");
            });
            start.countDown();

            assertEquals("CANCELLED", first.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals("CANCELLED", second.get(5, TimeUnit.SECONDS).getStatus());

            DiagnosisRunRecord storedRun = firstService.getDiagnosisRuns(incident.getId())
                    .orElseThrow()
                    .stream()
                    .filter(candidate -> run.getRunId().equals(candidate.getRunId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("CANCELLED", storedRun.getStatus());
            assertEquals(1, storedRun.getEvidence().stream()
                    .filter(evidence -> "diagnosis_cancelled".equals(evidence.getType()))
                    .count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completeRun_shouldNotOverwriteCancelledTerminalState() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload(
                "fp-cancel-complete", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord run = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");
        service.markRunRunning(incident.getId(), run.getRunId());
        service.cancelRun(incident.getId(), run.getRunId(), "用户取消");

        DiagnosisRunRecord unchanged = service.completeRun(
                incident.getId(), run.getRunId(), "# 不应保存的报告");

        assertEquals("CANCELLED", unchanged.getStatus());
        assertEquals(null, unchanged.getReport());
        assertEquals("用户取消", unchanged.getErrorMessage());
    }

    @Test
    void failRun_shouldNotOverwriteCancelledTerminalState() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload(
                "fp-cancel-fail", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord run = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");
        service.cancelRun(incident.getId(), run.getRunId(), "用户取消");

        DiagnosisRunRecord unchanged = service.failRun(
                incident.getId(), run.getRunId(), "后台线程晚到的失败");

        assertEquals("CANCELLED", unchanged.getStatus());
        assertEquals("用户取消", unchanged.getErrorMessage());
        assertEquals(0, unchanged.getEvidence().stream()
                .filter(item -> "failure_reason".equals(item.getType()))
                .count());
    }

    @Test
    void toolEvidence_shouldPersistWaitingToolProgressAndStructuredEvidence() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-2", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");

        DiagnosisRunRecord waiting = service.markRunWaitingTool(
                incident.getId(), queued.getRunId(), "queryPrometheusAlerts", "{\"range\":\"1h\"}");
        assertEquals("WAITING_TOOL", waiting.getStatus());
        assertEquals("queryPrometheusAlerts", waiting.getCurrentTool());
        assertEquals("正在调用工具 queryPrometheusAlerts", waiting.getCurrentStep());

        DiagnosisRunRecord updated = service.addToolEvidence(
                incident.getId(),
                queued.getRunId(),
                org.example.dto.DiagnosisEvidence.toolCall(
                        "queryPrometheusAlerts",
                        "{\"range\":\"1h\"}",
                        "now-1h to now",
                        "返回 2 条活动告警",
                        "{\"success\":true,\"alerts\":[{\"alertName\":\"HighCPUUsage\"}]}",
                        true,
                        null,
                        System.currentTimeMillis()));

        assertEquals("RUNNING", updated.getStatus());
        assertEquals("已完成工具调用 queryPrometheusAlerts", updated.getCurrentStep());
        assertEquals("返回 2 条活动告警", updated.getProgressMessage());
        assertEquals("queryPrometheusAlerts", updated.getEvidence().get(1).getToolName());
        assertEquals("{\"range\":\"1h\"}", updated.getEvidence().get(1).getQueryParams());
        assertEquals("now-1h to now", updated.getEvidence().get(1).getTimeRange());
        assertTrue(updated.getEvidence().get(1).isSuccess());
    }

    @Test
    void updateRunAlertContext_shouldReplacePersistedContextAndAlertContextEvidence() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-3", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "基础告警上下文");

        DiagnosisRunRecord updated = service.updateRunAlertContext(
                incident.getId(), queued.getRunId(), "基础告警上下文\n\n## 预取指标趋势证据");

        assertEquals("基础告警上下文\n\n## 预取指标趋势证据", updated.getAlertContext());
        assertEquals(1, updated.getEvidence().stream()
                .filter(evidence -> "alert_context".equals(evidence.getType()))
                .count());
        assertEquals("基础告警上下文\n\n## 预取指标趋势证据", updated.getEvidence().get(0).getContent());

        IncidentRecord restored = newIncidentService().getIncident(incident.getId()).orElseThrow();
        assertEquals("基础告警上下文\n\n## 预取指标趋势证据",
                restored.getDiagnosisRuns().get(0).getAlertContext());
    }

    @Test
    void completeRun_shouldApplyEvidenceGuard_whenGuardIsConfigured() {
        IncidentService service = newIncidentService(new DiagnosisReportService());
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-4", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");
        org.example.dto.DiagnosisEvidence evidence = org.example.dto.DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "15m",
                "cpu_usage 最近 15m 持续上升",
                "{\"success\":true}",
                true,
                null,
                System.currentTimeMillis());
        evidence.setId("ev-cpu-guard");
        service.addToolEvidence(incident.getId(), queued.getRunId(), evidence);

        DiagnosisRunRecord completed = service.completeRun(
                incident.getId(),
                queued.getRunId(),
                "# 告警分析报告\n\nCPU 持续上升 [evidence: ev-cpu-guard]");

        assertTrue(completed.getReport().contains("## 证据校验"));
        assertTrue(completed.getReport().contains("置信度: 高"));
        assertTrue(completed.getReport().contains("已引用证据: ev-cpu-guard"));
        assertTrue(completed.getQualityScore() >= 80);
        assertEquals("HIGH", completed.getQualityGrade());
        assertTrue(completed.getQualitySummary().contains("HIGH"));
    }

    @Test
    void createReusedDiagnosisRunIfAvailable_shouldReuseLatestCompletedReportForSameIncident() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-reuse", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord original = service.createDiagnosisRun(incident.getId(), "原始告警上下文");
        DiagnosisRunRecord completed = service.completeRun(
                incident.getId(),
                original.getRunId(),
                "# 告警分析报告\n\nCPU 持续高水位");

        DiagnosisRunRecord reused = service.createReusedDiagnosisRunIfAvailable(
                incident.getId(), "重复告警上下文").orElseThrow();

        assertEquals("COMPLETED", reused.getStatus());
        assertEquals(completed.getReport(), reused.getReport());
        assertEquals(completed.getRunId(), reused.getReusedFromRunId());
        assertEquals(1.0d, reused.getReuseConfidence());
        assertTrue(reused.getReuseValidatedAt() > 0);
        assertTrue(reused.getReuseReason().contains("同一 Incident 已存在完成诊断"));
        assertEquals("复用历史诊断报告", reused.getCurrentStep());
        assertEquals(List.of("alert_context", "diagnosis_reuse"), reused.getEvidence().stream()
                .map(evidence -> evidence.getType())
                .toList());

        IncidentRecord restored = newIncidentService().getIncident(incident.getId()).orElseThrow();
        assertEquals(2, restored.getDiagnosisRuns().size());
        assertEquals(completed.getRunId(), restored.getDiagnosisRuns().get(1).getReusedFromRunId());
    }

    @Test
    void createReusedDiagnosisRunIfAvailable_shouldReturnEmptyWhenNoCompletedReportExists() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-no-reuse", "HighCPUUsage", "payment-service"));
        service.createDiagnosisRun(incident.getId(), "告警上下文");

        assertFalse(service.createReusedDiagnosisRunIfAvailable(incident.getId(), "重复告警上下文").isPresent());
    }

    @Test
    void createReusedDiagnosisRunIfAvailable_shouldReturnEmptyWhenReuseIsDisabled() {
        AppIncidentProperties properties = incidentProperties();
        properties.setDiagnosisReuseEnabled(false);
        IncidentService service = newIncidentService(null, properties);
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-reuse-disabled", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord original = service.createDiagnosisRun(incident.getId(), "原始告警上下文");
        service.completeRun(incident.getId(), original.getRunId(), "# 告警分析报告");

        assertFalse(service.createReusedDiagnosisRunIfAvailable(incident.getId(), "重复告警上下文").isPresent());
    }

    @Test
    void confirmAndRejectRun_shouldPersistHumanReviewFields() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-review-1", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord completedSource = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");
        DiagnosisRunRecord completed = service.completeRun(incident.getId(), completedSource.getRunId(), "# 告警分析报告");

        DiagnosisRunRecord confirmed = service.confirmRun(incident.getId(), completed.getRunId(), "根因准确");

        assertEquals("CONFIRMED", confirmed.getHumanReviewStatus());
        assertEquals("根因准确", confirmed.getHumanReviewComment());
        assertTrue(confirmed.getHumanReviewedAt() > 0);

        DiagnosisRunRecord rejectedSource = service.createDiagnosisRun(incident.getId(), "另一次上下文");
        DiagnosisRunRecord rejectedCompleted = service.completeRun(incident.getId(), rejectedSource.getRunId(), "# 另一次报告");
        DiagnosisRunRecord rejected = service.rejectRun(incident.getId(), rejectedCompleted.getRunId(), "证据不足");

        assertEquals("REJECTED", rejected.getHumanReviewStatus());
        assertEquals("证据不足", rejected.getHumanReviewComment());
        assertTrue(rejected.getHumanReviewedAt() > 0);
    }

    @Test
    void confirmRun_shouldRejectNonCompletedRun() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-review-2", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.confirmRun(incident.getId(), queued.getRunId(), "还没完成"));

        assertTrue(exception.getMessage().contains("只有已完成诊断"));
    }

    @Test
    void markRunCaseArchived_shouldPersistArchiveResult() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-archive-1", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord source = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");
        DiagnosisRunRecord completed = service.completeRun(incident.getId(), source.getRunId(), "# 告警分析报告");

        DiagnosisRunRecord archived = service.markRunCaseArchived(
                incident.getId(), completed.getRunId(), true, "doc-1", "历史案例已写入知识库");

        assertTrue(archived.isCaseArchived());
        assertEquals("doc-1", archived.getCaseDocumentId());
        assertEquals("历史案例已写入知识库", archived.getCaseArchiveMessage());
    }

    @Test
    void listIncidents_shouldFilterByStatusSeverityRunStatusKeywordAndReviewStatus() {
        IncidentService service = newIncidentService();
        IncidentRecord cpuIncident = service.recordAlert(alertPayload("fp-filter-cpu", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord cpuRun = service.createDiagnosisRun(cpuIncident.getId(), "CPU 告警上下文");
        DiagnosisRunRecord completed = service.completeRun(cpuIncident.getId(), cpuRun.getRunId(), "# CPU 报告");
        service.confirmRun(cpuIncident.getId(), completed.getRunId(), "确认");

        IncidentRecord memoryIncident = service.recordAlert(alertPayload("fp-filter-memory", "HighMemoryUsage", "order-service"));
        DiagnosisRunRecord memoryRun = service.createDiagnosisRun(memoryIncident.getId(), "内存告警上下文");
        service.failRun(memoryIncident.getId(), memoryRun.getRunId(), "诊断失败");

        List<org.example.dto.IncidentSummary> filtered = service.listIncidents(
                "OPEN", "critical", "COMPLETED", "payment", "CONFIRMED");

        assertEquals(1, filtered.size());
        assertEquals(cpuIncident.getId(), filtered.get(0).getId());
        assertEquals(completed.getRunId(), filtered.get(0).getLatestRunId());
        assertEquals("COMPLETED", filtered.get(0).getLatestRunStatus());
        assertEquals("CONFIRMED", filtered.get(0).getLatestRunHumanReviewStatus());
    }

    @Test
    void getRunEvidence_shouldReturnEvidenceForSpecificRun() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-evidence-1", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord run = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");
        org.example.dto.DiagnosisEvidence evidence = org.example.dto.DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "1h",
                "CPU 上升",
                "{\"success\":true}",
                true,
                null,
                System.currentTimeMillis());
        service.addToolEvidence(incident.getId(), run.getRunId(), evidence);

        List<org.example.dto.DiagnosisEvidence> evidenceList =
                service.getRunEvidence(incident.getId(), run.getRunId()).orElseThrow();

        assertEquals(2, evidenceList.size());
        assertEquals("alert_context", evidenceList.get(0).getType());
        assertEquals("queryMetricTrend", evidenceList.get(1).getToolName());
    }

    private IncidentService newIncidentService() {
        return newIncidentService(null);
    }

    private IncidentService newIncidentService(DiagnosisReportService reportService) {
        return newIncidentService(reportService, incidentProperties());
    }

    private IncidentService newIncidentService(DiagnosisReportService reportService,
                                               AppIncidentProperties properties) {
        IncidentStore store = new IncidentStore(properties, new ObjectMapper(), dataSource(properties));
        return new IncidentService(store, new ObjectMapper(), reportService, properties);
    }

    private DataSource dataSource(AppIncidentProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(properties.getJdbcUrl());
        dataSource.setUsername(properties.getJdbcUsername());
        dataSource.setPassword(properties.getJdbcPassword());
        return dataSource;
    }

    private long rowCount(DataSource dataSource, String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from " + table);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private IncidentState incidentState(DataSource dataSource, String incidentId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select title, updated_at, version from incidents where id = ?
                     """)) {
            statement.setString(1, incidentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return new IncidentState(
                        resultSet.getString("title"),
                        resultSet.getLong("updated_at"),
                        resultSet.getLong("version"));
            }
        }
    }

    private AppIncidentProperties incidentProperties() {
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setPath(tempDir.resolve("incidents").toString());
        properties.setJdbcUrl("jdbc:h2:" + tempDir.resolve("incidents-db"));
        properties.setJdbcUsername("");
        properties.setJdbcPassword("");
        return properties;
    }

    private AlertPayload alertPayload(String fingerprint, String alertName, String service) {
        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setFingerprint(fingerprint);
        alert.setStartsAt("2026-05-13T00:00:00Z");
        alert.setLabels(Map.of(
                "alertname", alertName,
                "severity", "critical",
                "service", service,
                "instance", service + "-pod-1"
        ));
        alert.setAnnotations(Map.of("summary", alertName + " " + service));

        AlertPayload payload = new AlertPayload();
        payload.setReceiver("webhook");
        payload.setStatus("firing");
        payload.setCommonLabels(Map.of("severity", "critical", "service", service));
        payload.setCommonAnnotations(Map.of("summary", alertName + " " + service));
        payload.setAlerts(List.of(alert));
        payload.setExternalURL("http://alertmanager.example/alerts");
        return payload;
    }

    private AlertPayload alertPayloadWithoutFingerprint(String alertName,
                                                        String serviceLabel,
                                                        String jobLabel,
                                                        String instance) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        labels.put("alertname", alertName);
        labels.put("severity", "critical");
        labels.put("instance", instance);
        if (serviceLabel != null) {
            labels.put("service", serviceLabel);
        }
        if (jobLabel != null) {
            labels.put("job", jobLabel);
        }

        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setStartsAt("2026-05-30T10:00:00Z");
        alert.setLabels(labels);
        alert.setAnnotations(Map.of(
                "summary", "CPU 使用率超过 90%",
                "description", "CPU 95%"
        ));

        AlertPayload payload = new AlertPayload();
        payload.setReceiver("webhook");
        payload.setStatus("firing");
        payload.setCommonLabels(Map.of("severity", "critical"));
        payload.setCommonAnnotations(Map.of("summary", "CPU 使用率超过 90%"));
        payload.setAlerts(List.of(alert));
        return payload;
    }

    private record IncidentState(String title, long updatedAt, long version) {
    }

    private static final class RollbackTrackingDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicInteger rollbackCount = new AtomicInteger();

        private RollbackTrackingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return track(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return track(delegate.getConnection(username, password));
        }

        private Connection track(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("rollback".equals(method.getName())) {
                            rollbackCount.incrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private int rollbackCount() {
            return rollbackCount.get();
        }

        private void resetRollbackCount() {
            rollbackCount.set(0);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
