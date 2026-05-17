package org.example.dto;

import java.util.ArrayList;
import java.util.List;

public class DiagnosisRunRecord {

    private String runId;
    private String incidentId;
    private String status = "QUEUED";
    private long createdAt;
    private long startedAt;
    private long completedAt;
    private String alertContext;
    private String report;
    private String errorMessage;
    private String currentStep;
    private String progressMessage;
    private String currentTool;
    private List<DiagnosisEvidence> evidence = new ArrayList<>();

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(String incidentId) {
        this.incidentId = incidentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(long completedAt) {
        this.completedAt = completedAt;
    }

    public String getAlertContext() {
        return alertContext;
    }

    public void setAlertContext(String alertContext) {
        this.alertContext = alertContext;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public String getProgressMessage() {
        return progressMessage;
    }

    public void setProgressMessage(String progressMessage) {
        this.progressMessage = progressMessage;
    }

    public String getCurrentTool() {
        return currentTool;
    }

    public void setCurrentTool(String currentTool) {
        this.currentTool = currentTool;
    }

    public List<DiagnosisEvidence> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<DiagnosisEvidence> evidence) {
        this.evidence = evidence == null ? new ArrayList<>() : new ArrayList<>(evidence);
    }
}
