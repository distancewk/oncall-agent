package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppChatHistoryProperties;
import org.example.config.AppIncidentProperties;
import org.example.dto.ChatSessionRecord;
import org.example.dto.IncidentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class LegacyDataImporter implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyDataImporter.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final AppIncidentProperties incidentProperties;
    private final AppChatHistoryProperties chatProperties;
    private final IncidentSchemaMigrator schemaMigrator;

    public LegacyDataImporter(DataSource dataSource,
                              ObjectMapper objectMapper,
                              AppIncidentProperties incidentProperties,
                              AppChatHistoryProperties chatProperties,
                              IncidentSchemaMigrator schemaMigrator) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.incidentProperties = incidentProperties;
        this.chatProperties = chatProperties;
        this.schemaMigrator = schemaMigrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        importLegacyData();
    }

    public void importLegacyData() {
        schemaMigrator.migrate();
        importIncidentFiles();
        importIncidentPayloadRows();
        importChatFiles();
    }

    private void importIncidentFiles() {
        Path root = Path.of(incidentProperties.getPath()).normalize().toAbsolutePath();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(this::stageIncidentFile);
        } catch (Exception e) {
            LOGGER.warn("扫描旧 Incident 文件失败: {}", root, e);
        }
    }

    private void stageIncidentFile(Path path) {
        try {
            IncidentRecord record = objectMapper.readValue(path.toFile(), IncidentRecord.class);
            String payload = objectMapper.writeValueAsString(record);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         insert into incidents (
                             id, aggregation_key, title, status, severity, alert_count,
                             created_at, updated_at, last_alert_at, payload
                         )
                         select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                         where not exists (select 1 from incidents where id = ?)
                         """)) {
                Object[] columns = {
                        record.getId(), record.getAggregationKey(), record.getTitle(),
                        record.getStatus(), record.getSeverity(), record.getAlertCount(),
                        record.getCreatedAt(), record.getUpdatedAt(), record.getLastAlertAt()
                };
                for (int index = 0; index < columns.length; index++) {
                    statement.setObject(index + 1, columns[index]);
                }
                statement.setString(10, payload);
                statement.setString(11, record.getId());
                statement.executeUpdate();
            }
        } catch (Exception e) {
            LOGGER.warn("跳过损坏的旧 Incident 文件: {}", path, e);
        }
    }

    private void importIncidentPayloadRows() {
        List<LegacyIncidentPayload> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select id, payload from incidents
                     where payload is not null and payload <> '{}' and payload <> ''
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(new LegacyIncidentPayload(
                        resultSet.getString("id"), resultSet.getString("payload")));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取旧 Incident payload 失败", e);
        }
        rows.forEach(this::importIncidentPayload);
    }

    private void importIncidentPayload(LegacyIncidentPayload legacy) {
        String marker = "incident:" + legacy.incidentId();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (markerExists(connection, marker)) {
                    connection.commit();
                    return;
                }
                IncidentRecord record = objectMapper.readValue(legacy.payload(), IncidentRecord.class);
                IncidentAlertRepository alertRepository =
                        new IncidentAlertRepository(dataSource, objectMapper);
                DiagnosisEvidenceRepository evidenceRepository =
                        new DiagnosisEvidenceRepository(dataSource);
                DiagnosisRunRepository runRepository =
                        new DiagnosisRunRepository(dataSource, objectMapper, evidenceRepository);
                alertRepository.saveSnapshot(
                        connection, legacy.incidentId(), record.getAlertPayloads(), record.getLastAlertAt());
                for (var run : record.getDiagnosisRuns()) {
                    runRepository.save(connection, run);
                }
                insertMarker(connection, marker);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                LOGGER.warn("跳过损坏的旧 Incident payload: {}", legacy.incidentId(), e);
            }
        } catch (Exception e) {
            LOGGER.warn("导入旧 Incident payload 失败: {}", legacy.incidentId(), e);
        }
    }

    private void importChatFiles() {
        Path root = Path.of(chatProperties.getPath()).normalize().toAbsolutePath();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(this::importChatFile);
        } catch (Exception e) {
            LOGGER.warn("扫描旧聊天文件失败: {}", root, e);
        }
    }

    private void importChatFile(Path path) {
        String encodedName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
        String marker = "chat:" + encodedName;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (markerExists(connection, marker)) {
                    connection.commit();
                    return;
                }
                ChatSessionRecord record = objectMapper.readValue(path.toFile(), ChatSessionRecord.class);
                upsertChatSession(connection, record);
                try (PreparedStatement statement = connection.prepareStatement(
                        "delete from chat_messages where session_id = ?")) {
                    statement.setString(1, record.getSessionId());
                    statement.executeUpdate();
                }
                long createdAt = record.getUpdateTime();
                for (Map<String, String> message : record.getMessageHistory()) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            insert into chat_messages (session_id, role, content, created_at)
                            values (?, ?, ?, ?)
                            """)) {
                        statement.setString(1, record.getSessionId());
                        statement.setString(2, message.getOrDefault("role", ""));
                        statement.setString(3, message.getOrDefault("content", ""));
                        statement.setLong(4, createdAt++);
                        statement.executeUpdate();
                    }
                }
                insertMarker(connection, marker);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                LOGGER.warn("跳过损坏的旧聊天文件: {}", path, e);
            }
        } catch (Exception e) {
            LOGGER.warn("导入旧聊天文件失败: {}", path, e);
        }
    }

    private void upsertChatSession(Connection connection, ChatSessionRecord record) throws Exception {
        String title = chatTitle(record);
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                update chat_sessions set title = ?, updated_at = ? where session_id = ?
                """)) {
            statement.setString(1, title);
            statement.setLong(2, record.getUpdateTime());
            statement.setString(3, record.getSessionId());
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into chat_sessions (session_id, title, created_at, updated_at)
                    values (?, ?, ?, ?)
                    """)) {
                statement.setString(1, record.getSessionId());
                statement.setString(2, title);
                statement.setLong(3, record.getCreateTime());
                statement.setLong(4, record.getUpdateTime());
                statement.executeUpdate();
            }
        }
    }

    private String chatTitle(ChatSessionRecord record) {
        for (Map<String, String> message : record.getMessageHistory()) {
            if ("user".equals(message.get("role"))) {
                String content = message.getOrDefault("content", "").trim();
                if (!content.isEmpty()) {
                    return content.length() > 30 ? content.substring(0, 30) + "..." : content;
                }
            }
        }
        return "新对话";
    }

    private boolean markerExists(Connection connection, String marker) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from legacy_import_markers where marker_key = ?")) {
            statement.setString(1, marker);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void insertMarker(Connection connection, String marker) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into legacy_import_markers (marker_key, imported_at) values (?, ?)
                """)) {
            statement.setString(1, marker);
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private record LegacyIncidentPayload(String incidentId, String payload) {
    }
}
