package org.example.service;

import org.example.config.AppDataLifecycleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DataLifecycleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataLifecycleService.class);
    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final DataSource dataSource;
    private final IncidentSchemaMigrator schemaMigrator;
    private final AppDataLifecycleProperties properties;

    public DataLifecycleService(DataSource dataSource,
                                IncidentSchemaMigrator schemaMigrator,
                                AppDataLifecycleProperties properties) {
        this.dataSource = dataSource;
        this.schemaMigrator = schemaMigrator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.lifecycle.cleanup-delay-millis:3600000}")
    public void scheduledCleanup() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            LOGGER.info("数据生命周期清理完成: {}", cleanup(System.currentTimeMillis()));
        } catch (RuntimeException e) {
            LOGGER.error("数据生命周期清理失败，下一轮将重试", e);
        }
    }

    public Map<String, Integer> cleanup(long now) {
        schemaMigrator.migrate();
        Map<String, Integer> deleted = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                deleted.put("background_jobs", deleteTerminalRows(connection,
                        "background_jobs", properties.getTerminalJobRetentionDays(), now));
                deleted.put("index_tasks", deleteTerminalRows(connection,
                        "index_tasks", properties.getIndexTaskRetentionDays(), now));
                deleted.put("chat_sessions", deleteChatSessions(connection,
                        properties.getChatSessionRetentionDays(), now));
                deleted.put("security_audit_events", deleteByCreatedAt(connection,
                        "security_audit_events", properties.getSecurityAuditRetentionDays(), now));
                connection.commit();
                return deleted;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("清理历史数据失败", e);
        }
    }

    private int deleteTerminalRows(Connection connection, String table, int retentionDays, long now)
            throws Exception {
        if (retentionDays <= 0) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from " + table
                        + " where status in ('COMPLETED', 'FAILED', 'CANCELLED') and updated_at < ?")) {
            statement.setLong(1, cutoff(now, retentionDays));
            return statement.executeUpdate();
        }
    }

    private int deleteChatSessions(Connection connection, int retentionDays, long now) throws Exception {
        if (retentionDays <= 0) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from chat_sessions where updated_at < ?")) {
            statement.setLong(1, cutoff(now, retentionDays));
            return statement.executeUpdate();
        }
    }

    private int deleteByCreatedAt(Connection connection, String table, int retentionDays, long now)
            throws Exception {
        if (retentionDays <= 0) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from " + table + " where created_at < ?")) {
            statement.setLong(1, cutoff(now, retentionDays));
            return statement.executeUpdate();
        }
    }

    private long cutoff(long now, int retentionDays) {
        return now - Math.max(1L, retentionDays) * MILLIS_PER_DAY;
    }
}
