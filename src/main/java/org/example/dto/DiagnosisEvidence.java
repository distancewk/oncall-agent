package org.example.dto;

import java.util.Objects;

public class DiagnosisEvidence {

    private String id;
    private String type;
    private String title;
    private String content;
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
        evidence.setCreatedAt(createdAt);
        return evidence;
    }
}
