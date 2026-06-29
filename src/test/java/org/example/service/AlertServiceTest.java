package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.AlertPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
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

        String alertId = first.storeAlert(payload());
        first.storeReport(alertId, "# 报告");

        AlertService restarted = new AlertService(
                dataSource, new ObjectMapper(), new IncidentSchemaMigrator(dataSource));
        assertEquals(1, restarted.getAlerts().size());
        assertEquals(alertId, restarted.getAlert(alertId).getId());
        assertEquals("# 报告", restarted.getReport(alertId));
    }

    private AlertPayload payload() {
        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setLabels(Map.of("alertname", "HighCPUUsage", "severity", "critical"));
        AlertPayload payload = new AlertPayload();
        payload.setStatus("firing");
        payload.setReceiver("webhook");
        payload.setAlerts(List.of(alert));
        payload.setCommonLabels(Map.of("severity", "critical"));
        payload.setCommonAnnotations(Map.of("summary", "CPU high"));
        return payload;
    }
}
