package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.AlertPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AlertIdentity {
    private AlertIdentity() {
    }

    public static String id(AlertPayload payload, ObjectMapper objectMapper) {
        String identity = eventIdentity(payload, objectMapper);
        return "alert-" + sha256(identity).substring(0, 48);
    }

    /**
     * Returns the stable Incident aggregation key for a webhook. Explicit
     * Alertmanager groups take precedence; otherwise a multi-alert payload is
     * grouped by the complete, order-independent alert set. Single-alert
     * fingerprint behavior remains backward compatible.
     */
    public static String correlationKey(AlertPayload payload, ObjectMapper objectMapper) {
        if (payload == null) {
            return null;
        }
        if (notBlank(payload.getGroupKey())) {
            return "alertmanager-group:" + sha256(payload.getGroupKey()).substring(0, 40);
        }
        List<AlertPayload.Alert> alerts = payload.getAlerts() == null
                ? List.of() : payload.getAlerts();
        if (alerts.size() > 1) {
            return "multi-alert:" + sha256(String.join("|", eventKeys(payload, objectMapper))).substring(0, 40);
        }
        if (alerts.size() == 1 && notBlank(alerts.get(0).getFingerprint())) {
            return "fingerprint:" + alerts.get(0).getFingerprint();
        }
        return null;
    }

    private static String eventIdentity(AlertPayload payload, ObjectMapper objectMapper) {
        if (payload == null) {
            return "null-payload";
        }
        List<String> keys = eventKeys(payload, objectMapper);
        if (!keys.isEmpty()) {
            return "group=" + value(payload.getGroupKey())
                    + "|status=" + value(payload.getStatus())
                    + "|alerts=" + String.join("|", keys);
        }
        if (notBlank(payload.getGroupKey())) {
            return "group=" + payload.getGroupKey() + "|status=" + value(payload.getStatus());
        }
        try {
            return (objectMapper == null ? new ObjectMapper() : objectMapper)
                    .writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }

    private static List<String> eventKeys(AlertPayload payload, ObjectMapper objectMapper) {
        List<String> keys = new ArrayList<>();
        if (payload == null || payload.getAlerts() == null) {
            return keys;
        }
        for (AlertPayload.Alert alert : payload.getAlerts()) {
            if (alert == null) {
                continue;
            }
            if (notBlank(alert.getFingerprint())) {
                keys.add("fingerprint=" + alert.getFingerprint()
                        + ";status=" + value(alert.getStatus())
                        + ";startsAt=" + value(alert.getStartsAt())
                        + ";endsAt=" + value(alert.getEndsAt()));
                continue;
            }
            try {
                keys.add((objectMapper == null ? new ObjectMapper() : objectMapper)
                        .writeValueAsString(alert));
            } catch (Exception e) {
                keys.add(String.valueOf(alert));
            }
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成告警幂等键", e);
        }
    }
}
