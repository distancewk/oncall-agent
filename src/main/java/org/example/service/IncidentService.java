package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.dto.IncidentSummary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class IncidentService {

    private final IncidentStore incidentStore;
    private final ObjectMapper objectMapper;

    public IncidentService(IncidentStore incidentStore, ObjectMapper objectMapper) {
        this.incidentStore = incidentStore;
        this.objectMapper = objectMapper;
    }

    public synchronized IncidentRecord recordAlert(AlertPayload payload) {
        String aggregationKey = aggregationKey(payload);
        long now = System.currentTimeMillis();
        IncidentRecord record = incidentStore.findByAggregationKey(aggregationKey)
                .orElseGet(() -> newIncident(payload, aggregationKey, now));

        record.setTitle(title(payload));
        record.setSeverity(severity(payload));
        record.setStatus(status(payload));
        record.setUpdatedAt(now);
        record.setLastAlertAt(now);
        record.setAlertCount(record.getAlertCount() + 1);
        record.getAlertPayloads().add(payload);
        incidentStore.save(record);
        return record;
    }

    public synchronized List<IncidentSummary> listIncidents() {
        return incidentStore.list().stream()
                .map(this::summary)
                .sorted(Comparator.comparingLong(IncidentSummary::getUpdatedAt).reversed())
                .toList();
    }

    public synchronized Optional<IncidentRecord> getIncident(String incidentId) {
        return incidentStore.load(incidentId);
    }

    public synchronized Optional<List<DiagnosisRunRecord>> getDiagnosisRuns(String incidentId) {
        return getIncident(incidentId).map(record -> new ArrayList<>(record.getDiagnosisRuns()));
    }

    public synchronized DiagnosisRunRecord createDiagnosisRun(String incidentId, String alertContext) {
        IncidentRecord incident = requireIncident(incidentId);
        long now = System.currentTimeMillis();
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-" + UUID.randomUUID().toString().substring(0, 12));
        run.setIncidentId(incidentId);
        run.setStatus("QUEUED");
        run.setCreatedAt(now);
        run.setAlertContext(alertContext);
        run.getEvidence().add(DiagnosisEvidence.of("alert_context", "注入给 AI 的告警上下文", alertContext, now));

        incident.getDiagnosisRuns().add(run);
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public synchronized DiagnosisRunRecord markRunRunning(String incidentId, String runId) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        long now = System.currentTimeMillis();
        run.setStatus("RUNNING");
        run.setStartedAt(now);
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public synchronized DiagnosisRunRecord completeRun(String incidentId, String runId, String report) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        long now = System.currentTimeMillis();
        if (run.getStartedAt() == 0L) {
            run.setStartedAt(now);
        }
        run.setStatus("COMPLETED");
        run.setCompletedAt(now);
        run.setReport(report);
        run.setErrorMessage(null);
        run.getEvidence().add(DiagnosisEvidence.of("final_report", "最终诊断报告", report, now));
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public synchronized DiagnosisRunRecord failRun(String incidentId, String runId, String errorMessage) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        long now = System.currentTimeMillis();
        if (run.getStartedAt() == 0L) {
            run.setStartedAt(now);
        }
        run.setStatus("FAILED");
        run.setCompletedAt(now);
        run.setErrorMessage(errorMessage);
        run.getEvidence().add(DiagnosisEvidence.of("failure_reason", "诊断失败原因", errorMessage, now));
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public String buildAlertContext(IncidentRecord incident) {
        StringBuilder builder = new StringBuilder();
        builder.append("Incident ID: ").append(incident.getId()).append('\n');
        builder.append("聚合键: ").append(incident.getAggregationKey()).append('\n');
        builder.append("标题: ").append(incident.getTitle()).append('\n');
        builder.append("级别: ").append(incident.getSeverity()).append('\n');
        builder.append("状态: ").append(incident.getStatus()).append('\n');
        builder.append("累计告警次数: ").append(incident.getAlertCount()).append('\n');

        List<AlertPayload> payloads = incident.getAlertPayloads();
        if (!payloads.isEmpty()) {
            builder.append('\n').append("最新告警原文摘要:").append('\n');
            builder.append(buildAlertContext(payloads.get(payloads.size() - 1)));
        }
        return builder.toString();
    }

    public String buildAlertContext(AlertPayload payload) {
        StringBuilder builder = new StringBuilder();
        builder.append("告警状态: ").append(payload.getStatus()).append('\n');
        builder.append("接收者: ").append(payload.getReceiver()).append('\n');

        appendMap(builder, "公共标签", payload.getCommonLabels());
        appendMap(builder, "公共注解", payload.getCommonAnnotations());

        builder.append("告警列表:").append('\n');
        if (payload.getAlerts() != null) {
            for (int i = 0; i < payload.getAlerts().size(); i++) {
                AlertPayload.Alert alert = payload.getAlerts().get(i);
                builder.append("  [").append(i + 1).append("]").append('\n');
                builder.append("    状态: ").append(alert.getStatus()).append('\n');
                builder.append("    开始时间: ").append(alert.getStartsAt()).append('\n');
                builder.append("    结束时间: ").append(alert.getEndsAt()).append('\n');
                builder.append("    fingerprint: ").append(alert.getFingerprint()).append('\n');
                appendMap(builder, "    标签", alert.getLabels());
                appendMap(builder, "    注解", alert.getAnnotations());
            }
        }
        return builder.toString();
    }

    public String rawPayloadEvidence(AlertPayload payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }

    private IncidentRecord newIncident(AlertPayload payload, String aggregationKey, long now) {
        IncidentRecord record = new IncidentRecord();
        record.setId("inc-" + UUID.randomUUID().toString().substring(0, 12));
        record.setAggregationKey(aggregationKey);
        record.setTitle(title(payload));
        record.setSeverity(severity(payload));
        record.setStatus(status(payload));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setLastAlertAt(now);
        record.setAlertCount(0);
        return record;
    }

    private IncidentRecord requireIncident(String incidentId) {
        return incidentStore.load(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Incident 不存在: " + incidentId));
    }

    private DiagnosisRunRecord requireRun(IncidentRecord incident, String runId) {
        return incident.getDiagnosisRuns().stream()
                .filter(run -> runId.equals(run.getRunId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DiagnosisRun 不存在: " + runId));
    }

    private IncidentSummary summary(IncidentRecord record) {
        IncidentSummary summary = new IncidentSummary();
        summary.setId(record.getId());
        summary.setTitle(record.getTitle());
        summary.setStatus(record.getStatus());
        summary.setSeverity(record.getSeverity());
        summary.setAlertCount(record.getAlertCount());
        summary.setCreatedAt(record.getCreatedAt());
        summary.setUpdatedAt(record.getUpdatedAt());
        summary.setLastAlertAt(record.getLastAlertAt());
        if (!record.getDiagnosisRuns().isEmpty()) {
            summary.setLatestRunStatus(record.getDiagnosisRuns()
                    .get(record.getDiagnosisRuns().size() - 1)
                    .getStatus());
        }
        return summary;
    }

    private String aggregationKey(AlertPayload payload) {
        AlertPayload.Alert alert = firstAlert(payload);
        if (alert != null && notBlank(alert.getFingerprint())) {
            return "fingerprint:" + alert.getFingerprint();
        }

        Map<String, String> labels = alert != null ? alert.getLabels() : Map.of();
        String alertName = label(labels, "alertname", "unknown-alert");
        String service = label(labels, "service", label(labels, "job", "unknown-service"));
        String instance = label(labels, "instance", "unknown-instance");
        String severity = label(labels, "severity", severity(payload));
        return String.join("|", alertName, service, instance, severity).toLowerCase(Locale.ROOT);
    }

    private String title(AlertPayload payload) {
        String summary = value(payload.getCommonAnnotations(), "summary");
        if (notBlank(summary)) {
            return summary;
        }
        AlertPayload.Alert alert = firstAlert(payload);
        if (alert != null) {
            summary = value(alert.getAnnotations(), "summary");
            if (notBlank(summary)) {
                return summary;
            }
            String alertName = value(alert.getLabels(), "alertname");
            String service = value(alert.getLabels(), "service");
            if (notBlank(alertName) && notBlank(service)) {
                return alertName + " " + service;
            }
            if (notBlank(alertName)) {
                return alertName;
            }
        }
        return "未知告警";
    }

    private String severity(AlertPayload payload) {
        String severity = value(payload.getCommonLabels(), "severity");
        if (notBlank(severity)) {
            return severity;
        }
        AlertPayload.Alert alert = firstAlert(payload);
        if (alert != null) {
            severity = value(alert.getLabels(), "severity");
            if (notBlank(severity)) {
                return severity;
            }
        }
        return "unknown";
    }

    private String status(AlertPayload payload) {
        return "resolved".equalsIgnoreCase(payload.getStatus()) ? "RESOLVED" : "OPEN";
    }

    private AlertPayload.Alert firstAlert(AlertPayload payload) {
        if (payload.getAlerts() == null || payload.getAlerts().isEmpty()) {
            return null;
        }
        return payload.getAlerts().get(0);
    }

    private void appendMap(StringBuilder builder, String title, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append(title).append(':').append('\n');
        values.forEach((key, value) -> builder
                .append("  - ")
                .append(key)
                .append(": ")
                .append(value)
                .append('\n'));
    }

    private String label(Map<String, String> labels, String key, String fallback) {
        String value = value(labels, key);
        return notBlank(value) ? value : fallback;
    }

    private String value(Map<String, String> map, String key) {
        if (map == null) {
            return null;
        }
        return map.get(key);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
