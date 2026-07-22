package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.AlertPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void alertsAndReports_shouldSurviveServiceRecreation() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:" + tempDir.resolve("alerts-db"));
        dataSource.setUsername("");
        dataSource.setPassword("");
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        AlertService first = new AlertService(dataSource, new ObjectMapper(), migrator);

        String alertId = first.storeAlert(payload("fp-alert-1"));
        first.storeReport(alertId, "# 报告");

        AlertService restarted = new AlertService(
                dataSource, new ObjectMapper(), new IncidentSchemaMigrator(dataSource));
        assertEquals(1, restarted.getAlerts().size());
        assertEquals(alertId, restarted.getAlert(alertId).getId());
        assertEquals("# 报告", restarted.getReport(alertId));
    }

    @Test
    void storeMissingReportsForIncident_shouldOnlyFillUnreportedAlerts() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:" + tempDir.resolve("incident-alert-reports-db"));
        dataSource.setUsername("");
        dataSource.setPassword("");
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        AlertService service = new AlertService(dataSource, new ObjectMapper(), migrator);
        insertIncident(dataSource, "incident-1");

        String reportedAlert = service.storeAlert(payload("fp-alert-reported"), "incident-1");
        String pendingAlert = service.storeAlert(payload("fp-alert-pending"), "incident-1");
        service.storeReport(reportedAlert, "旧报告");

        service.storeMissingReportsForIncident("incident-1", "最新报告");

        assertEquals("旧报告", service.getReport(reportedAlert));
        assertEquals("最新报告", service.getReport(pendingAlert));
    }

    private AlertPayload payload() {
        return payload("fp-alert-1");
    }

    private AlertPayload payload(String fingerprint) {
        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setFingerprint(fingerprint);
        alert.setLabels(Map.of("alertname", "HighCPUUsage", "severity", "critical"));
        AlertPayload payload = new AlertPayload();
        payload.setStatus("firing");
        payload.setReceiver("webhook");
        payload.setAlerts(List.of(alert));
        payload.setCommonLabels(Map.of("severity", "critical"));
        payload.setCommonAnnotations(Map.of("summary", "CPU high"));
        return payload;
    }

    private void insertIncident(DriverManagerDataSource dataSource, String incidentId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into incidents (
                         id, aggregation_key, title, status, severity, alert_count,
                         created_at, updated_at, last_alert_at, payload
                     ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, incidentId);
            statement.setString(2, "aggregation-1");
            statement.setString(3, "HighCPUUsage");
            statement.setString(4, "OPEN");
            statement.setString(5, "critical");
            statement.setInt(6, 0);
            statement.setLong(7, 1L);
            statement.setLong(8, 1L);
            statement.setLong(9, 1L);
            statement.setString(10, "{}");
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
