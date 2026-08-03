package org.example.service;

import org.example.config.AppDataLifecycleProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataLifecycleServiceTest {

    @TempDir
    private Path tempDir;

    private DataSource dataSource;
    private long now;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl("jdbc:h2:" + tempDir.resolve("lifecycle-db"));
        source.setUsername("");
        source.setPassword("");
        dataSource = source;
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        migrator.migrate();
        now = 2_000_000_000L;

        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("""
                    insert into background_jobs (
                        job_id, job_type, business_key, payload, status,
                        attempt_count, max_attempts, available_at, cancel_requested,
                        created_at, updated_at
                    ) values ('old-job', 'INDEX', 'old-task', '{}', 'COMPLETED', 1, 1, 1, false, 1, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    insert into index_tasks (
                        task_id, file_name, file_path, document_id, content_hash,
                        status, created_at, updated_at
                    ) values ('old-task', 'runbook.md', '/tmp/runbook.md', 'doc-1', 'hash-1',
                              'COMPLETED', 1, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    insert into chat_sessions (session_id, title, created_at, updated_at)
                    values ('old-session', 'old', 1, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    insert into chat_messages (session_id, role, content, created_at)
                    values ('old-session', 'user', 'old', 1)
                    """);
            connection.createStatement().executeUpdate("""
                    insert into security_audit_events (
                        audit_id, event_type, actor, http_method, request_path,
                        outcome, status_code, created_at
                    ) values ('old-audit', 'API_READ', 'old-actor', 'GET', '/api/x',
                              'SUCCESS', 200, 1)
                    """);
        }
    }

    @Test
    void cleanup_shouldDeleteOnlyConfiguredTerminalData() throws Exception {
        AppDataLifecycleProperties properties = new AppDataLifecycleProperties();
        properties.setEnabled(true);
        properties.setTerminalJobRetentionDays(1);
        properties.setIndexTaskRetentionDays(1);
        properties.setChatSessionRetentionDays(1);
        properties.setSecurityAuditRetentionDays(1);

        DataLifecycleService service = new DataLifecycleService(
                dataSource, new IncidentSchemaMigrator(dataSource), properties);

        Map<String, Integer> deleted = service.cleanup(now);

        assertEquals(1, deleted.get("background_jobs"));
        assertEquals(1, deleted.get("index_tasks"));
        assertEquals(1, deleted.get("chat_sessions"));
        assertEquals(1, deleted.get("security_audit_events"));
        assertEquals(0L, count("background_jobs"));
        assertEquals(0L, count("index_tasks"));
        assertEquals(0L, count("chat_sessions"));
        assertEquals(0L, count("chat_messages"));
        assertEquals(0L, count("security_audit_events"));
    }

    private long count(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select count(*) from " + table);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
