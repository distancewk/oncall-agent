package org.example.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppIncidentProperties;
import org.example.config.AppJobProperties;
import org.example.dto.AlertPayload;
import org.example.dto.BackgroundJobRecord;
import org.example.dto.ChatSessionRecord;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.dto.IndexTaskStatus;
import org.example.service.BackgroundJobRepository;
import org.example.service.ChatHistoryRepository;
import org.example.service.IncidentSchemaMigrator;
import org.example.service.IncidentStore;
import org.example.service.IndexTaskRepository;
import org.example.service.IndexTaskStatusService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlDurableWorkflowIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static DriverManagerDataSource dataSource;

    @TempDir
    private Path tempDir;

    private IncidentStore incidentStore;
    private BackgroundJobRepository backgroundJobRepository;
    private ChatHistoryRepository chatHistoryRepository;
    private IndexTaskRepository indexTaskRepository;

    @BeforeAll
    static void migratePostgresqlSchema() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        new IncidentSchemaMigrator(dataSource).migrate();
    }

    @BeforeEach
    void setUpRepositories() throws Exception {
        truncateDurableTables();
        AppIncidentProperties incidentProperties = new AppIncidentProperties();
        incidentProperties.setPath(tempDir.resolve("incidents").toString());
        incidentProperties.setJdbcUrl(POSTGRES.getJdbcUrl());
        incidentProperties.setJdbcUsername(POSTGRES.getUsername());
        incidentProperties.setJdbcPassword(POSTGRES.getPassword());
        incidentStore = new IncidentStore(
                incidentProperties,
                OBJECT_MAPPER,
                dataSource,
                new IncidentSchemaMigrator(dataSource)
        );
        backgroundJobRepository = new BackgroundJobRepository(dataSource);
        chatHistoryRepository = new ChatHistoryRepository(dataSource);
        indexTaskRepository = new IndexTaskRepository(dataSource);
    }

    @Test
    void productionSqlShouldPersistDurableWorkflowRecordsOnPostgresql() throws Exception {
        IncidentRecord incident = incident("inc-postgres-1", "cpu|payments|critical", 100L);
        incident.setAlertPayloads(List.of(
                alertPayload("alert-a", "payments", "critical"),
                alertPayload("alert-b", "payments", "warning")
        ));
        DiagnosisRunRecord run = diagnosisRun("run-postgres-1", incident.getId(), 120L);
        run.setEvidence(List.of(evidence("ev-postgres-1", "initial evidence", 130L)));
        incident.setDiagnosisRuns(List.of(run));

        incidentStore.save(incident);

        run.setStatus("COMPLETED");
        run.setCompletedAt(200L);
        run.setReport("root cause confirmed");
        run.setEvidence(List.of(evidence("ev-postgres-1", "updated evidence", 210L)));
        incident.setTitle("CPU saturation mitigated");
        incident.setStatus("RESOLVED");
        incident.setAlertCount(2);
        incident.setUpdatedAt(220L);
        incidentStore.save(incident);

        IncidentRecord restored = incidentStore.load(incident.getId()).orElseThrow();
        assertEquals("CPU saturation mitigated", restored.getTitle());
        assertEquals("RESOLVED", restored.getStatus());
        assertEquals(2, restored.getAlertPayloads().size());
        assertEquals(1, restored.getDiagnosisRuns().size());
        assertEquals("COMPLETED", restored.getDiagnosisRuns().get(0).getStatus());
        assertEquals("updated evidence", restored.getDiagnosisRuns().get(0).getEvidence().get(0).getSummary());
        assertEquals(1L, countRows("diagnosis_evidence", "id = 'ev-postgres-1'"));
        assertEquals(2L, countRows("incident_alerts", "incident_id = 'inc-postgres-1'"));

        chatHistoryRepository.appendMessagePair("chat-postgres-1", 300L, 310L,
                "first title", "what happened?", "cpu saturated");
        ChatSessionRecord chat = chatHistoryRepository.load("chat-postgres-1").orElseThrow();
        chat.setUpdateTime(320L);
        chat.setMessageHistory(new ArrayList<>(List.of(
                Map.of("role", "user", "content", "updated question"),
                Map.of("role", "assistant", "content", "updated answer")
        )));
        chatHistoryRepository.save(chat, "updated title");
        assertEquals(2, chatHistoryRepository.load("chat-postgres-1")
                .orElseThrow()
                .getMessageHistory()
                .size());
        assertEquals("chat-postgres-1", chatHistoryRepository.listSessions().get(0).getSessionId());

        IndexTaskStatus indexTask = indexTask("index-postgres-1", 400L);
        indexTaskRepository.insert(indexTask);
        assertEquals("INDEXING", indexTaskRepository.find(indexTask.getTaskId()).orElseThrow().getStatus());

        BackgroundJobRecord completed = backgroundJobRepository.enqueue(
                "DIAGNOSIS", "run-postgres-1", "{\"runId\":\"run-postgres-1\"}", 2, 500L);
        BackgroundJobRecord claimed = backgroundJobRepository.claimNext("worker-finish", 510L, 100L)
                .orElseThrow();
        assertEquals(completed.getJobId(), claimed.getJobId());
        assertTrue(backgroundJobRepository.heartbeat(claimed.getJobId(), "worker-finish", 520L, 100L));
        backgroundJobRepository.complete(claimed.getJobId(), "worker-finish", 530L);
        assertEquals("COMPLETED", backgroundJobRepository.findById(claimed.getJobId())
                .orElseThrow()
                .getStatus());

        BackgroundJobRecord cancelled = backgroundJobRepository.enqueue(
                "DIAGNOSIS", "run-cancel", "{}", 2, 600L);
        assertTrue(backgroundJobRepository.requestCancel("DIAGNOSIS", "run-cancel", 610L));
        assertEquals("CANCELLED", backgroundJobRepository.findById(cancelled.getJobId())
                .orElseThrow()
                .getStatus());

        BackgroundJobRecord recovered = backgroundJobRepository.enqueue(
                "DOCUMENT_INDEX", "index-postgres-recover", "{}", 2, 700L);
        backgroundJobRepository.claimNext("worker-recover", 710L, 1L).orElseThrow();
        assertEquals(1, backgroundJobRepository.recoverExpiredLeases(712L));
        BackgroundJobRecord recoveredAfterLease = backgroundJobRepository.findById(recovered.getJobId())
                .orElseThrow();
        assertEquals("RETRY", recoveredAfterLease.getStatus());
        assertEquals(1, recoveredAfterLease.getAttemptCount());
        assertEquals(712L, recoveredAfterLease.getAvailableAt());
    }

    @Test
    void concurrentClaimShouldLeaseQueuedJobToOnlyOnePostgresqlWorker() throws Exception {
        BackgroundJobRecord queued = backgroundJobRepository.enqueue(
                "DIAGNOSIS", "run-concurrent-claim", "{}", 3, 1_000L);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<BackgroundJobRecord>> first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return backgroundJobRepository.claimNext("worker-a", 1_010L, 1_000L);
            });
            Future<Optional<BackgroundJobRecord>> second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return backgroundJobRepository.claimNext("worker-b", 1_010L, 1_000L);
            });

            start.countDown();

            Optional<BackgroundJobRecord> firstClaim = first.get(10, TimeUnit.SECONDS);
            Optional<BackgroundJobRecord> secondClaim = second.get(10, TimeUnit.SECONDS);
            long presentCount = List.of(firstClaim, secondClaim).stream()
                    .filter(Optional::isPresent)
                    .count();
            assertEquals(1L, presentCount);

            BackgroundJobRecord persisted = backgroundJobRepository.findById(queued.getJobId())
                    .orElseThrow();
            assertEquals("RUNNING", persisted.getStatus());
            assertTrue(List.of("worker-a", "worker-b").contains(persisted.getLeaseOwner()));
            assertEquals(1, persisted.getAttemptCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void postgresqlTransactionsShouldRollbackDiagnosisAndIndexPartialRows() throws Exception {
        IncidentRecord incident = incident("inc-rollback-1", "rollback|diagnosis", 2_000L);
        incidentStore.save(incident);
        DiagnosisRunRecord first = diagnosisRun("run-rollback-1", incident.getId(), 2_010L);
        first.setEvidence(List.of(evidence("ev-rollback-1", "transaction evidence", 2_020L)));
        DiagnosisRunRecord duplicate = diagnosisRun("run-rollback-1", incident.getId(), 2_030L);

        assertThrows(IllegalStateException.class, () -> incidentStore.inTransaction(connection -> {
            incidentStore.persistNewDiagnosisRun(connection, incident.getId(), first, 2_100L);
            incidentStore.persistNewDiagnosisRun(connection, incident.getId(), duplicate, 2_200L);
            return null;
        }));

        assertEquals(0L, countRows("diagnosis_runs", "run_id = 'run-rollback-1'"));
        assertEquals(0L, countRows("diagnosis_evidence", "id = 'ev-rollback-1'"));
        assertEquals(2_000L, incidentStore.load(incident.getId()).orElseThrow().getUpdatedAt());

        IndexTaskStatusService failingIndexService = new IndexTaskStatusService(
                dataSource,
                new IncidentSchemaMigrator(dataSource),
                new FailingBackgroundJobRepository(dataSource),
                OBJECT_MAPPER,
                new AppJobProperties()
        );

        assertThrows(IllegalStateException.class,
                () -> failingIndexService.createTaskAndEnqueue("runbook.md", "/tmp/runbook.md"));

        assertTrue(failingIndexService.listStatuses().isEmpty());
        assertEquals(0L, countRows("background_jobs", "job_type = 'DOCUMENT_INDEX'"));
    }

    private void truncateDurableTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    truncate table
                        diagnosis_evidence,
                        diagnosis_runs,
                        incident_alerts,
                        chat_messages,
                        chat_sessions,
                        index_tasks,
                        background_jobs,
                        incidents,
                        legacy_import_markers
                    restart identity cascade
                    """);
        }
    }

    private long countRows(String tableName, String whereClause) throws Exception {
        String sql = "select count(*) from " + tableName + " where " + whereClause;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private IncidentRecord incident(String incidentId, String aggregationKey, long now) {
        IncidentRecord record = new IncidentRecord();
        record.setId(incidentId);
        record.setAggregationKey(aggregationKey);
        record.setTitle("CPU saturation");
        record.setStatus("OPEN");
        record.setSeverity("critical");
        record.setAlertCount(1);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setLastAlertAt(now);
        return record;
    }

    private DiagnosisRunRecord diagnosisRun(String runId, String incidentId, long now) {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId(runId);
        run.setIncidentId(incidentId);
        run.setStatus("RUNNING");
        run.setCreatedAt(now);
        run.setStartedAt(now);
        run.setAlertContext("alert context");
        run.setCurrentStep("collecting evidence");
        run.setProgressMessage("running");
        run.setCurrentTool("queryMetricTrend");
        run.setQualityScore(80);
        run.setQualityGrade("B");
        run.setQualitySummary("usable");
        run.setQualityIssues(List.of("needs follow up"));
        return run;
    }

    private DiagnosisEvidence evidence(String evidenceId, String summary, long now) {
        DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "15m",
                summary,
                "{\"series\":[1,2,3]}",
                true,
                null,
                now
        );
        evidence.setId(evidenceId);
        return evidence;
    }

    private AlertPayload alertPayload(String fingerprint, String service, String severity) {
        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setFingerprint(fingerprint);
        alert.setLabels(Map.of(
                "alertname", "HighCpuUsage",
                "service", service,
                "severity", severity
        ));
        alert.setAnnotations(Map.of("summary", "CPU usage is high"));

        AlertPayload payload = new AlertPayload();
        payload.setReceiver("postgres-it");
        payload.setStatus("firing");
        payload.setGroupKey("group-" + fingerprint);
        payload.setGroupLabels(Map.of("alertname", "HighCpuUsage"));
        payload.setCommonLabels(Map.of("service", service, "severity", severity));
        payload.setCommonAnnotations(Map.of("summary", "CPU usage is high"));
        payload.setAlerts(List.of(alert));
        return payload;
    }

    private IndexTaskStatus indexTask(String taskId, long now) {
        IndexTaskStatus status = new IndexTaskStatus();
        status.setTaskId(taskId);
        status.setFileName("runbook.md");
        status.setFilePath("/tmp/runbook.md");
        status.setStatus("INDEXING");
        status.setMessage("文件已接收，索引处理中");
        status.setCreatedAt(now);
        status.setUpdatedAt(now);
        return status;
    }

    private static final class FailingBackgroundJobRepository extends BackgroundJobRepository {

        private FailingBackgroundJobRepository(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public BackgroundJobRecord enqueue(Connection connection,
                                           String jobType,
                                           String businessKey,
                                           String payload,
                                           int maxAttempts,
                                           long now) throws SQLException {
            assertNotNull(connection);
            throw new SQLException("simulated duplicate job failure", "23505");
        }
    }
}
