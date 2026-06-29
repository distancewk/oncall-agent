package org.example.dto;

public class IncidentSummary {

    private String id;
    private String title;
    private String status;
    private String severity;
    private int alertCount;
    private String latestRunId;
    private String latestRunStatus;
    private int latestRunQualityScore;
    private String latestRunQualityGrade;
    private String latestRunHumanReviewStatus;
    private boolean latestRunCaseArchived;
    private long createdAt;
    private long updatedAt;
    private long lastAlertAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public int getAlertCount() {
        return alertCount;
    }

    public void setAlertCount(int alertCount) {
        this.alertCount = alertCount;
    }

    public String getLatestRunId() {
        return latestRunId;
    }

    public void setLatestRunId(String latestRunId) {
        this.latestRunId = latestRunId;
    }

    public String getLatestRunStatus() {
        return latestRunStatus;
    }

    public void setLatestRunStatus(String latestRunStatus) {
        this.latestRunStatus = latestRunStatus;
    }

    public int getLatestRunQualityScore() {
        return latestRunQualityScore;
    }

    public void setLatestRunQualityScore(int latestRunQualityScore) {
        this.latestRunQualityScore = latestRunQualityScore;
    }

    public String getLatestRunQualityGrade() {
        return latestRunQualityGrade;
    }

    public void setLatestRunQualityGrade(String latestRunQualityGrade) {
        this.latestRunQualityGrade = latestRunQualityGrade;
    }

    public String getLatestRunHumanReviewStatus() {
        return latestRunHumanReviewStatus;
    }

    public void setLatestRunHumanReviewStatus(String latestRunHumanReviewStatus) {
        this.latestRunHumanReviewStatus = latestRunHumanReviewStatus;
    }

    public boolean isLatestRunCaseArchived() {
        return latestRunCaseArchived;
    }

    public void setLatestRunCaseArchived(boolean latestRunCaseArchived) {
        this.latestRunCaseArchived = latestRunCaseArchived;
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
}
