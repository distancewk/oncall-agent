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
    private String reusedFromRunId;
    private String reuseReason;
    private double reuseConfidence;
    private long reuseValidatedAt;
    private int qualityScore;
    private String qualityGrade;
    private String qualitySummary;
    private List<String> qualityIssues = new ArrayList<>();
    private String humanReviewStatus = "UNREVIEWED";
    private String humanReviewComment;
    private long humanReviewedAt;
    private boolean caseArchived;
    private String caseDocumentId;
    private String caseArchiveMessage;
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

    public String getReusedFromRunId() {
        return reusedFromRunId;
    }

    public void setReusedFromRunId(String reusedFromRunId) {
        this.reusedFromRunId = reusedFromRunId;
    }

    public String getReuseReason() {
        return reuseReason;
    }

    public void setReuseReason(String reuseReason) {
        this.reuseReason = reuseReason;
    }

    public double getReuseConfidence() {
        return reuseConfidence;
    }

    public void setReuseConfidence(double reuseConfidence) {
        this.reuseConfidence = reuseConfidence;
    }

    public long getReuseValidatedAt() {
        return reuseValidatedAt;
    }

    public void setReuseValidatedAt(long reuseValidatedAt) {
        this.reuseValidatedAt = reuseValidatedAt;
    }

    public int getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(int qualityScore) {
        this.qualityScore = qualityScore;
    }

    public String getQualityGrade() {
        return qualityGrade;
    }

    public void setQualityGrade(String qualityGrade) {
        this.qualityGrade = qualityGrade;
    }

    public String getQualitySummary() {
        return qualitySummary;
    }

    public void setQualitySummary(String qualitySummary) {
        this.qualitySummary = qualitySummary;
    }

    public List<String> getQualityIssues() {
        return qualityIssues;
    }

    public void setQualityIssues(List<String> qualityIssues) {
        this.qualityIssues = qualityIssues == null ? new ArrayList<>() : new ArrayList<>(qualityIssues);
    }

    public String getHumanReviewStatus() {
        return humanReviewStatus;
    }

    public void setHumanReviewStatus(String humanReviewStatus) {
        this.humanReviewStatus = humanReviewStatus;
    }

    public String getHumanReviewComment() {
        return humanReviewComment;
    }

    public void setHumanReviewComment(String humanReviewComment) {
        this.humanReviewComment = humanReviewComment;
    }

    public long getHumanReviewedAt() {
        return humanReviewedAt;
    }

    public void setHumanReviewedAt(long humanReviewedAt) {
        this.humanReviewedAt = humanReviewedAt;
    }

    public boolean isCaseArchived() {
        return caseArchived;
    }

    public void setCaseArchived(boolean caseArchived) {
        this.caseArchived = caseArchived;
    }

    public String getCaseDocumentId() {
        return caseDocumentId;
    }

    public void setCaseDocumentId(String caseDocumentId) {
        this.caseDocumentId = caseDocumentId;
    }

    public String getCaseArchiveMessage() {
        return caseArchiveMessage;
    }

    public void setCaseArchiveMessage(String caseArchiveMessage) {
        this.caseArchiveMessage = caseArchiveMessage;
    }

    public List<DiagnosisEvidence> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<DiagnosisEvidence> evidence) {
        this.evidence = evidence == null ? new ArrayList<>() : new ArrayList<>(evidence);
    }
}
