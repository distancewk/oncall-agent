package org.example.dto;

import java.util.Objects;
import java.util.UUID;

public class DiagnosisEvidence {

    private String id;
    private String type;
    private String title;
    private String content;
    private String toolName;
    private String queryParams;
    private String timeRange;
    private String summary;
    private String rawFragment;
    private boolean success;
    private String errorMessage;
    private String errorCode;
    private long createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(String queryParams) {
        this.queryParams = queryParams;
    }

    public String getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(String timeRange) {
        this.timeRange = timeRange;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getRawFragment() {
        return rawFragment;
    }

    public void setRawFragment(String rawFragment) {
        this.rawFragment = rawFragment;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public static DiagnosisEvidence of(String type, String title, String content, long createdAt) {
        DiagnosisEvidence evidence = new DiagnosisEvidence();
        evidence.setId("ev-" + Objects.hash(type, title, content, createdAt));
        evidence.setType(type);
        evidence.setTitle(title);
        evidence.setContent(content);
        evidence.setSummary(content);
        evidence.setRawFragment(content);
        evidence.setSuccess(true);
        evidence.setCreatedAt(createdAt);
        return evidence;
    }

    public static DiagnosisEvidence toolCall(String toolName,
                                             String queryParams,
                                             String timeRange,
                                             String summary,
                                             String rawFragment,
                                             boolean success,
                                             String errorMessage,
                                             long createdAt) {
        DiagnosisEvidence evidence = new DiagnosisEvidence();
        evidence.setId("ev-" + UUID.randomUUID().toString().substring(0, 12));
        evidence.setType("tool_call");
        evidence.setTitle("工具调用: " + toolName);
        evidence.setContent(summary);
        evidence.setToolName(toolName);
        evidence.setQueryParams(queryParams);
        evidence.setTimeRange(timeRange);
        evidence.setSummary(summary);
        evidence.setRawFragment(rawFragment);
        evidence.setSuccess(success);
        evidence.setErrorMessage(errorMessage);
        evidence.setErrorCode(null);
        evidence.setCreatedAt(createdAt);
        return evidence;
    }
}
