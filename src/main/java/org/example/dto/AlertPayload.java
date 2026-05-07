package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 映射 Alertmanager Webhook 推送的 JSON Payload
 */
@Setter
@Getter
public class AlertPayload {

    private String receiver;
    private String status;
    private List<Alert> alerts;
    private Map<String, String> groupLabels;
    private Map<String, String> commonLabels;
    private Map<String, String> commonAnnotations;
    private String externalURL;
    private String version;
    private String groupKey;

    @Setter
    @Getter
    public static class Alert {
        private String status;
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String startsAt;
        private String endsAt;
        private String generatorURL;
        private String fingerprint;
    }
}
