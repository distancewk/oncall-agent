package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.AlertPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertIdentityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void id_shouldIncludeAllAlertsAndRemainOrderIndependent() {
        AlertPayload firstOrder = payload(List.of(alert("fp-a"), alert("fp-b")));
        AlertPayload reversedOrder = payload(List.of(alert("fp-b"), alert("fp-a")));
        AlertPayload changedAlert = payload(List.of(alert("fp-a"), alert("fp-c")));

        assertEquals(AlertIdentity.id(firstOrder, objectMapper),
                AlertIdentity.id(reversedOrder, objectMapper));
        assertNotEquals(AlertIdentity.id(firstOrder, objectMapper),
                AlertIdentity.id(changedAlert, objectMapper));
    }

    @Test
    void correlationKey_shouldUseExplicitGroupOrCompleteMultiAlertSet() {
        AlertPayload grouped = payload(List.of(alert("fp-a"), alert("fp-b")));
        grouped.setGroupKey("{service=payments}");
        AlertPayload ungrouped = payload(List.of(alert("fp-a"), alert("fp-b")));

        assertTrue(AlertIdentity.correlationKey(grouped, objectMapper)
                .startsWith("alertmanager-group:"));
        assertTrue(AlertIdentity.correlationKey(ungrouped, objectMapper)
                .startsWith("multi-alert:"));
    }

    private AlertPayload payload(List<AlertPayload.Alert> alerts) {
        AlertPayload payload = new AlertPayload();
        payload.setStatus("firing");
        payload.setAlerts(alerts);
        return payload;
    }

    private AlertPayload.Alert alert(String fingerprint) {
        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setFingerprint(fingerprint);
        alert.setStartsAt("2026-07-29T00:00:00Z");
        alert.setLabels(Map.of("alertname", "TestAlert"));
        return alert;
    }
}
