package org.example.dto;

import java.util.ArrayList;
import java.util.List;

public class IncidentRecord {

    private String id;
    private String aggregationKey;
    private String title;
    private String status = "OPEN";
    private String severity = "unknown";
    private long createdAt;
    private long updatedAt;
    private long lastAlertAt;
    private int alertCount;
    private List<AlertPayload> alertPayloads = new ArrayList<>();
    private List<DiagnosisRunRecord> diagnosisRuns = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAggregationKey() {
        return aggregationKey;
    }

    public void setAggregationKey(String aggregationKey) {
        this.aggregationKey = aggregationKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getLastAlertAt() {
        return lastAlertAt;
    }

    public void setLastAlertAt(long lastAlertAt) {
        this.lastAlertAt = lastAlertAt;
    }

    public int getAlertCount() {
        return alertCount;
    }

    public void setAlertCount(int alertCount) {
        this.alertCount = alertCount;
    }

    public List<AlertPayload> getAlertPayloads() {
        return alertPayloads;
    }

    public void setAlertPayloads(List<AlertPayload> alertPayloads) {
        this.alertPayloads = alertPayloads == null ? new ArrayList<>() : new ArrayList<>(alertPayloads);
    }

    public List<DiagnosisRunRecord> getDiagnosisRuns() {
        return diagnosisRuns;
    }

    public void setDiagnosisRuns(List<DiagnosisRunRecord> diagnosisRuns) {
        this.diagnosisRuns = diagnosisRuns == null ? new ArrayList<>() : new ArrayList<>(diagnosisRuns);
    }
}
