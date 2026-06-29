package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppChatHistoryProperties;
import org.example.config.AppIncidentProperties;
import org.example.dto.AlertPayload;
import org.example.dto.ChatSessionRecord;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyDataImporterTest {

    @TempDir
    private Path tempDir;

    private DataSource dataSource;
    private ObjectMapper objectMapper;
    private AppIncidentProperties incidentProperties;
    private AppChatHistoryProperties chatProperties;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl("jdbc:h2:" + tempDir.resolve("legacy-db"));
        source.setUsername("");
        source.setPassword("");
        dataSource = source;
        objectMapper = new ObjectMapper();
        incidentProperties = new AppIncidentProperties();
        incidentProperties.setPath(tempDir.resolve("incidents").toString());
        chatProperties = new AppChatHistoryProperties();
        chatProperties.setPath(tempDir.resolve("chat-history").toString());
        new IncidentSchemaMigrator(dataSource).migrate();
    }

    @Test
    void importLegacyData_shouldBeIdempotentForIncidentPayloadAndChatFiles() throws Exception {
        IncidentRecord legacyIncident = incident();
        insertLegacyIncident(legacyIncident.getId(), legacyIncident.getAggregationKey(),
                objectMapper.writeValueAsString(legacyIncident));
        Files.createDirectories(Path.of(chatProperties.getPath()));
        Path chatFile = Path.of(chatProperties.getPath()).resolve("legacy-session.json");
        objectMapper.writeValue(chatFile.toFile(), chat());

        LegacyDataImporter importer = new LegacyDataImporter(
                dataSource, objectMapper, incidentProperties, chatProperties,
                new IncidentSchemaMigrator(dataSource));
        importer.importLegacyData();
        importer.importLegacyData();

        assertEquals(1, count("incident_alerts"));
        assertEquals(1, count("diagnosis_runs"));
        assertEquals(2, count("diagnosis_evidence"));
        assertEquals(1, count("chat_sessions"));
        assertEquals(2, count("chat_messages"));
        assertEquals(2, count("legacy_import_markers"));
        assertTrue(Files.exists(chatFile));
    }

    @Test
    void importLegacyData_shouldSkipCorruptPayloadWithoutDeletingSource() throws Exception {
        insertLegacyIncident("inc-corrupt", "corrupt-key", "{not-json");

        LegacyDataImporter importer = new LegacyDataImporter(
                dataSource, objectMapper, incidentProperties, chatProperties,
                new IncidentSchemaMigrator(dataSource));
        importer.importLegacyData();

        assertEquals(0, count("incident_alerts"));
        assertEquals(0, count("legacy_import_markers"));
        assertEquals("{not-json", selectPayload("inc-corrupt"));
    }

    private IncidentRecord incident() {
        AlertPayload payload = new AlertPayload();
        payload.setStatus("firing");
        payload.setCommonLabels(Map.of("severity", "critical"));

        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-legacy");
        run.setIncidentId("inc-legacy");
        run.setStatus("COMPLETED");
        run.setCreatedAt(100L);
        run.setStartedAt(110L);
        run.setCompletedAt(120L);
        run.setReport("# legacy report");
        run.getEvidence().add(DiagnosisEvidence.of("alert_context", "context", "legacy", 101L));
        run.getEvidence().add(DiagnosisEvidence.of("root_cause", "cause", "cpu", 119L));

        IncidentRecord incident = new IncidentRecord();
        incident.setId("inc-legacy");
        incident.setAggregationKey("legacy-key");
        incident.setTitle("Legacy CPU");
        incident.setStatus("OPEN");
        incident.setSeverity("critical");
        incident.setAlertCount(1);
        incident.setCreatedAt(100L);
        incident.setUpdatedAt(120L);
        incident.setLastAlertAt(100L);
        incident.setAlertPayloads(List.of(payload));
        incident.setDiagnosisRuns(List.of(run));
        return incident;
    }

    private ChatSessionRecord chat() {
        ChatSessionRecord chat = new ChatSessionRecord();
        chat.setSessionId("legacy-session");
        chat.setCreateTime(100L);
        chat.setUpdateTime(200L);
        chat.setMessageHistory(List.of(
                Map.of("role", "user", "content", "hello"),
                Map.of("role", "assistant", "content", "hi")
        ));
        return chat;
    }

    private void insertLegacyIncident(String id, String aggregationKey, String payload) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into incidents (
                         id, aggregation_key, title, status, severity, alert_count,
                         created_at, updated_at, last_alert_at, payload
                     ) values (?, ?, 'legacy', 'OPEN', 'critical', 1, 100, 100, 100, ?)
                     """)) {
            statement.setString(1, id);
            statement.setString(2, aggregationKey);
            statement.setString(3, payload);
            statement.executeUpdate();
        }
    }

    private int count(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select count(*) from " + table);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String selectPayload(String incidentId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select payload from incidents where id = ?")) {
            statement.setString(1, incidentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }
}
