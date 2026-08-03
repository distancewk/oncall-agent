package org.example.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SecurityAuditServiceTest {

    private SecurityAuditService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:security-audit-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new IncidentSchemaMigrator(dataSource).migrate();
        service = new SecurityAuditService(dataSource);
    }

    @Test
    void recordAndRecent_shouldPersistBoundedLowSensitivityEvent() {
        service.record(SecurityAuditService.AuditEvent.request(
                "DIAGNOSIS_CONFIRM", "operator-a", "OPERATOR", "machine",
                "jti-123", "POST", "/api/incidents/inc-1/runs/run-1/confirm",
                "SUCCESS", 200, "request-1", "trace-1", "http_status=200"));

        List<SecurityAuditService.AuditEvent> events = service.recent(10);

        assertEquals(1, events.size());
        assertEquals("DIAGNOSIS_CONFIRM", events.get(0).eventType());
        assertEquals("operator-a", events.get(0).actor());
        assertEquals("jti-123", events.get(0).tokenId());
        assertNotNull(events.get(0).createdAt());
    }

    @Test
    void recent_shouldClampRequestedLimit() {
        service.record(SecurityAuditService.AuditEvent.request(
                "API_READ", "anonymous", null, null, null, "GET", "/api/x",
                "DENIED", 401, null, null, "http_status=401"));

        assertEquals(1, service.recent(0).size());
        assertEquals(1, service.recent(1000).size());
    }

    @Test
    void record_shouldUseTenantCapturedByAuditEvent() {
        service.record(SecurityAuditService.AuditEvent.request(
                "WEBHOOK_ALERT", "anonymous", "WEBHOOK", null, null, "POST",
                "/api/webhook/alert", "SUCCESS", 200, "request-tenant-a", null,
                "http_status=200", "tenant-a"));

        try (TenantContext.Scope ignored = TenantContext.open("tenant-a")) {
            List<SecurityAuditService.AuditEvent> events = service.recent(10);
            assertEquals(1, events.size());
            assertEquals("tenant-a", events.get(0).tenantId());
        }
    }
}
