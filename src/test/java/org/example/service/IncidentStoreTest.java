package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppIncidentProperties;
import org.example.dto.AlertPayload;
import org.example.dto.IncidentRecord;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentStoreTest {

    @TempDir
    private Path tempDir;

    @Test
    void incidentStore_shouldPersistToJdbcAndNotWriteRuntimeJsonFiles() throws Exception {
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setJdbcUrl("jdbc:h2:" + tempDir.resolve("incidents-db"));
        properties.setJdbcUsername("");
        properties.setJdbcPassword("");
        properties.setPath(tempDir.resolve("legacy-json").toString());
        DataSource dataSource = dataSource(properties);
        IncidentStore store = new IncidentStore(properties, new ObjectMapper(), dataSource);

        IncidentRecord record = new IncidentRecord();
        record.setId("inc-jdbc-1");
        record.setAggregationKey("cpu_high|payment|pod-1|critical");
        record.setTitle("CPU 使用率超过 90%");
        record.setStatus("OPEN");
        record.setSeverity("critical");
        record.setAlertCount(2);
        record.setCreatedAt(100L);
        record.setUpdatedAt(200L);
        record.setLastAlertAt(180L);
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-jdbc-1");
        run.setIncidentId(record.getId());
        run.setStatus("RUNNING");
        run.setCreatedAt(150L);
        DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                "queryMetricTrend", "{\"metric\":\"cpu_usage\"}", "1h",
                "CPU 上升", "{\"success\":true}", true, null, 160L);
        evidence.setId("ev-jdbc-1");
        run.getEvidence().add(evidence);
        record.getDiagnosisRuns().add(run);

        store.save(record);

        IncidentRecord restored = store.load("inc-jdbc-1").orElseThrow();
        assertEquals("CPU 使用率超过 90%", restored.getTitle());
        assertEquals("cpu_high|payment|pod-1|critical", restored.getAggregationKey());
        assertEquals(1, store.list().size());
        assertTrue(store.findByAggregationKey("cpu_high|payment|pod-1|critical").isPresent());
        assertFalse(tempDir.resolve("legacy-json").resolve("inc-jdbc-1.json").toFile().exists());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select aggregation_key, severity, status, alert_count from incidents where id='inc-jdbc-1'")) {
            assertTrue(resultSet.next());
            assertEquals("cpu_high|payment|pod-1|critical", resultSet.getString("aggregation_key"));
            assertEquals("critical", resultSet.getString("severity"));
            assertEquals("OPEN", resultSet.getString("status"));
            assertEquals(2, resultSet.getInt("alert_count"));
        }

        assertTableExists(dataSource, "INCIDENT_ALERTS");
        assertTableExists(dataSource, "DIAGNOSIS_RUNS");
        assertTableExists(dataSource, "DIAGNOSIS_EVIDENCE");
        assertTableExists(dataSource, "CHAT_SESSIONS");
        assertTableExists(dataSource, "CHAT_MESSAGES");
        assertTableExists(dataSource, "INDEX_TASKS");
        assertTableExists(dataSource, "BACKGROUND_JOBS");
        assertTableExists(dataSource, "LEGACY_IMPORT_MARKERS");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet runs = statement.executeQuery(
                    "select status from diagnosis_runs where run_id='run-jdbc-1'")) {
                assertTrue(runs.next());
                assertEquals("RUNNING", runs.getString("status"));
            }
            try (ResultSet evidenceRows = statement.executeQuery(
                    "select tool_name from diagnosis_evidence where id='ev-jdbc-1'")) {
                assertTrue(evidenceRows.next());
                assertEquals("queryMetricTrend", evidenceRows.getString("tool_name"));
            }
        }
    }

    @Test
    void save_shouldUpdateExistingJdbcIncidentWithoutDuplicatingRows() throws Exception {
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setJdbcUrl("jdbc:h2:" + tempDir.resolve("upsert-db"));
        properties.setJdbcUsername("");
        properties.setJdbcPassword("");
        properties.setPath(tempDir.resolve("legacy-json-upsert").toString());
        DataSource dataSource = dataSource(properties);
        IncidentStore store = new IncidentStore(properties, new ObjectMapper(), dataSource);

        IncidentRecord record = new IncidentRecord();
        record.setId("inc-upsert-1");
        record.setAggregationKey("cpu_high|payment|pod-2|critical");
        record.setTitle("CPU 使用率超过 90%");
        record.setStatus("OPEN");
        record.setSeverity("critical");
        record.setAlertCount(1);
        record.setCreatedAt(100L);
        record.setUpdatedAt(200L);
        record.setLastAlertAt(200L);
        store.save(record);

        record.setTitle("CPU 使用率恢复观察");
        record.setStatus("ACKNOWLEDGED");
        record.setAlertCount(2);
        record.setUpdatedAt(300L);
        store.save(record);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select count(*) as row_count,
                            max(title) as title,
                            max(status) as status,
                            max(alert_count) as alert_count
                     from incidents
                     where id = ?
                     """)) {
            statement.setString(1, "inc-upsert-1");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("row_count"));
                assertEquals("CPU 使用率恢复观察", resultSet.getString("title"));
                assertEquals("ACKNOWLEDGED", resultSet.getString("status"));
                assertEquals(2, resultSet.getInt("alert_count"));
            }
        }
    }

    @Test
    void incidentStore_shouldIsolateIncidentReadsAndAggregationByTenant() throws Exception {
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setJdbcUrl("jdbc:h2:" + tempDir.resolve("tenant-isolation-db"));
        properties.setJdbcUsername("");
        properties.setJdbcPassword("");
        properties.setPath(tempDir.resolve("legacy-json-tenant").toString());
        IncidentStore store = new IncidentStore(properties, new ObjectMapper(), dataSource(properties));

        IncidentRecord tenantA = new IncidentRecord();
        tenantA.setId("inc-tenant-a");
        tenantA.setTenantId("tenant-a");
        tenantA.setAggregationKey("same-alert");
        tenantA.setTitle("Tenant A");
        tenantA.setCreatedAt(100L);
        tenantA.setUpdatedAt(100L);
        tenantA.setLastAlertAt(100L);
        IncidentRecord tenantB = new IncidentRecord();
        tenantB.setId("inc-tenant-b");
        tenantB.setTenantId("tenant-b");
        tenantB.setAggregationKey("same-alert");
        tenantB.setTitle("Tenant B");
        tenantB.setCreatedAt(100L);
        tenantB.setUpdatedAt(100L);
        tenantB.setLastAlertAt(100L);

        try (TenantContext.Scope ignored = TenantContext.open("tenant-a")) {
            store.save(tenantA);
            assertEquals("tenant-a", store.load("inc-tenant-a").orElseThrow().getTenantId());
        }
        try (TenantContext.Scope ignored = TenantContext.open("tenant-b")) {
            store.save(tenantB);
            assertTrue(store.load("inc-tenant-a").isEmpty());
            assertEquals("Tenant B", store.findByAggregationKey("same-alert").orElseThrow().getTitle());
            assertEquals(1, store.list().size());
        }
        try (TenantContext.Scope ignored = TenantContext.open("tenant-a")) {
            assertEquals("Tenant A", store.findByAggregationKey("same-alert").orElseThrow().getTitle());
            assertEquals(1, store.list().size());
        }
    }

    @Test
    void incidentStore_shouldAllowSameAlertIdInDifferentTenants() throws Exception {
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setJdbcUrl("jdbc:h2:" + tempDir.resolve("tenant-alert-db"));
        properties.setJdbcUsername("");
        properties.setJdbcPassword("");
        properties.setPath(tempDir.resolve("legacy-json-alert").toString());
        IncidentStore store = new IncidentStore(properties, new ObjectMapper(), dataSource(properties));
        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setFingerprint("same-fingerprint");
        AlertPayload payload = new AlertPayload();
        payload.setStatus("firing");
        payload.setAlerts(List.of(alert));

        IncidentRecord tenantA;
        IncidentRecord tenantB;
        try (TenantContext.Scope ignored = TenantContext.open("tenant-a")) {
            IncidentRecord candidate = alertCandidate("inc-alert-a", "tenant-a");
            tenantA = store.recordAlert(candidate, payload, 100L, "same-alert-id");
        }
        try (TenantContext.Scope ignored = TenantContext.open("tenant-b")) {
            IncidentRecord candidate = alertCandidate("inc-alert-b", "tenant-b");
            tenantB = store.recordAlert(candidate, payload, 100L, "same-alert-id");
            assertEquals("tenant-b", tenantB.getTenantId());
            assertEquals(1, store.list().size());
        }
        assertTrue(!tenantA.getId().equals(tenantB.getId()));
    }

    private IncidentRecord alertCandidate(String id, String tenantId) {
        IncidentRecord candidate = new IncidentRecord();
        candidate.setId(id);
        candidate.setTenantId(tenantId);
        candidate.setAggregationKey("fingerprint:same-fingerprint");
        candidate.setTitle("same alert");
        candidate.setCreatedAt(100L);
        return candidate;
    }

    private DataSource dataSource(AppIncidentProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(properties.getJdbcUrl());
        dataSource.setUsername(properties.getJdbcUsername());
        dataSource.setPassword(properties.getJdbcPassword());
        return dataSource;
    }

    private void assertTableExists(DataSource dataSource, String tableName) throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet tables = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            assertTrue(tables.next(), "Expected table to exist: " + tableName);
        }
    }
}
