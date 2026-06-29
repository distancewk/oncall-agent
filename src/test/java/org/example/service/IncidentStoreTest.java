package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppIncidentProperties;
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
