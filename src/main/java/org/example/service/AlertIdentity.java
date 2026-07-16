package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.AlertPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class AlertIdentity {
    private AlertIdentity() {
    }

    public static String id(AlertPayload payload, ObjectMapper objectMapper) {
        String fingerprint = null;
        if (payload != null && payload.getAlerts() != null) {
            fingerprint = payload.getAlerts().stream()
                    .map(AlertPayload.Alert::getFingerprint)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse(null);
        }
        String identity = fingerprint;
        if (identity == null && payload != null && payload.getGroupKey() != null) {
            identity = payload.getGroupKey();
        }
        if (identity == null) {
            try {
                identity = (objectMapper == null ? new ObjectMapper() : objectMapper)
                        .writeValueAsString(payload);
            } catch (Exception e) {
                identity = String.valueOf(payload);
            }
        }
        return "alert-" + sha256(identity).substring(0, 48);
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
