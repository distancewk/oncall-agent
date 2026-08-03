package org.example.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persists low-sensitivity security and operator action audit events. */
@Service
public class SecurityAuditService {

    private static final int MAX_RECENT_EVENTS = 200;
    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityAuditService.class);
    private final DataSource dataSource;

    @Autowired
    public SecurityAuditService(DataSource dataSource, IncidentSchemaMigrator schemaMigrator) {
        schemaMigrator.migrate();
        this.dataSource = dataSource;
    }

    public SecurityAuditService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Best-effort by design: audit storage failure must not turn a valid
     * operator action into an unknown business outcome.
     */
    public void record(AuditEvent event) {
        if (event == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into security_audit_events (
                         tenant_id, audit_id, event_type, actor, role, token_kind, token_id,
                         http_method, request_path, outcome, status_code,
                         request_id, trace_id, detail, created_at
                     ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, TenantContext.normalize(event.tenantId()));
            statement.setString(2, value(event.auditId(), 64));
            statement.setString(3, value(event.eventType(), 64));
            statement.setString(4, value(event.actor(), 128, "anonymous"));
            setNullable(statement, 5, value(event.role(), 32));
            setNullable(statement, 6, value(event.tokenKind(), 32));
            setNullable(statement, 7, value(event.tokenId(), 128));
            statement.setString(8, value(event.httpMethod(), 16, "UNKNOWN"));
            statement.setString(9, value(event.requestPath(), 512, "/"));
            statement.setString(10, value(event.outcome(), 32, "UNKNOWN"));
            statement.setInt(11, event.statusCode());
            setNullable(statement, 12, value(event.requestId(), 128));
            setNullable(statement, 13, value(event.traceId(), 128));
            setNullable(statement, 14, value(event.detail(), 256));
            statement.setLong(15, event.createdAt() > 0L
                    ? event.createdAt() : System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            // Do not log event fields; they can contain resource identifiers.
            LOGGER.warn("安全审计事件写入失败", e);
        }
    }

    public List<AuditEvent> recent(int requestedLimit) {
        int limit = Math.max(1, Math.min(MAX_RECENT_EVENTS, requestedLimit));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select tenant_id, audit_id, event_type, actor, role, token_kind, token_id,
                            http_method, request_path, outcome, status_code,
                            request_id, trace_id, detail, created_at
                     from security_audit_events
                     where tenant_id = ?
                     order by created_at desc, audit_id desc
                     limit ?
                     """)) {
            statement.setString(1, TenantContext.currentTenant());
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AuditEvent> events = new ArrayList<>();
                while (resultSet.next()) {
                    events.add(new AuditEvent(
                            resultSet.getString("audit_id"),
                            resultSet.getString("event_type"),
                            resultSet.getString("actor"),
                            resultSet.getString("role"),
                            resultSet.getString("token_kind"),
                            resultSet.getString("token_id"),
                            resultSet.getString("http_method"),
                            resultSet.getString("request_path"),
                            resultSet.getString("outcome"),
                            resultSet.getInt("status_code"),
                            resultSet.getString("request_id"),
                            resultSet.getString("trace_id"),
                            resultSet.getString("detail"),
                            resultSet.getString("tenant_id"),
                            resultSet.getLong("created_at")));
                }
                return events;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取安全审计事件失败", e);
        }
    }

    public record AuditEvent(String auditId, String eventType, String actor, String role,
                             String tokenKind, String tokenId, String httpMethod,
                             String requestPath, String outcome, int statusCode,
                             String requestId, String traceId, String detail,
                             String tenantId, long createdAt) {

        public static AuditEvent request(String eventType, String actor, String role,
                                         String tokenKind, String tokenId, String httpMethod,
                                         String requestPath, String outcome, int statusCode,
                                         String requestId, String traceId, String detail) {
            return new AuditEvent("audit-" + UUID.randomUUID(), eventType, actor, role,
                    tokenKind, tokenId, httpMethod, requestPath, outcome, statusCode,
                    requestId, traceId, detail, TenantContext.currentTenant(), System.currentTimeMillis());
        }

        public static AuditEvent request(String eventType, String actor, String role,
                                         String tokenKind, String tokenId, String httpMethod,
                                         String requestPath, String outcome, int statusCode,
                                         String requestId, String traceId, String detail,
                                         String tenantId) {
            return new AuditEvent("audit-" + UUID.randomUUID(), eventType, actor, role,
                    tokenKind, tokenId, httpMethod, requestPath, outcome, statusCode,
                    requestId, traceId, detail, TenantContext.normalize(tenantId),
                    System.currentTimeMillis());
        }
    }

    private void setNullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private String value(String input, int maxLength) {
        return value(input, maxLength, null);
    }

    private String value(String input, int maxLength, String fallback) {
        if (input == null || input.isBlank()) {
            return fallback;
        }
        String normalized = input.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
