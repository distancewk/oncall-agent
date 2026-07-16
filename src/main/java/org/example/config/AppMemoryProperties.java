package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.memory")
public class AppMemoryProperties {

    private static final int MIN_PRIVATE_RECALL_PROMPT_CHARS = 1;

    private boolean privateRecallEnabled = true;
    private boolean privateRecallGatingEnabled = true;
    private int privateRecallTopK = 3;
    private float privateRecallMinScore = 0.0f;
    private int privateRecallMaxPromptChars = 1200;
    private long extractionDebounceMillis = 5_000L;
    private int extractionBatchSize = 6;
    private int extractionMaxQueueMessages = 100;
    private int extractionMaxPromptChars = 6_000;
    private int extractionMaxFacts = 8;

    public boolean isPrivateRecallEnabled() {
        return privateRecallEnabled;
    }

    public void setPrivateRecallEnabled(boolean privateRecallEnabled) {
        this.privateRecallEnabled = privateRecallEnabled;
    }

    public boolean isPrivateRecallGatingEnabled() {
        return privateRecallGatingEnabled;
    }

    public void setPrivateRecallGatingEnabled(boolean privateRecallGatingEnabled) {
        this.privateRecallGatingEnabled = privateRecallGatingEnabled;
    }

    public int getPrivateRecallTopK() {
        return privateRecallTopK;
    }

    public void setPrivateRecallTopK(int privateRecallTopK) {
        this.privateRecallTopK = privateRecallTopK;
    }

    public float getPrivateRecallMinScore() {
        return privateRecallMinScore;
    }

    public void setPrivateRecallMinScore(float privateRecallMinScore) {
        this.privateRecallMinScore = privateRecallMinScore;
    }

    public int getPrivateRecallMaxPromptChars() {
        return Math.max(MIN_PRIVATE_RECALL_PROMPT_CHARS, privateRecallMaxPromptChars);
    }

    public void setPrivateRecallMaxPromptChars(int privateRecallMaxPromptChars) {
        this.privateRecallMaxPromptChars = Math.max(MIN_PRIVATE_RECALL_PROMPT_CHARS, privateRecallMaxPromptChars);
    }

    public long getExtractionDebounceMillis() {
        return Math.max(0L, extractionDebounceMillis);
    }

    public void setExtractionDebounceMillis(long extractionDebounceMillis) {
        this.extractionDebounceMillis = Math.max(0L, extractionDebounceMillis);
    }

    public int getExtractionBatchSize() {
        return Math.max(1, extractionBatchSize);
    }

    public void setExtractionBatchSize(int extractionBatchSize) {
        this.extractionBatchSize = Math.max(1, extractionBatchSize);
    }

    public int getExtractionMaxQueueMessages() {
        return Math.max(1, extractionMaxQueueMessages);
    }

    public void setExtractionMaxQueueMessages(int extractionMaxQueueMessages) {
        this.extractionMaxQueueMessages = Math.max(1, extractionMaxQueueMessages);
    }

    public int getExtractionMaxPromptChars() {
        return Math.max(256, extractionMaxPromptChars);
    }

    public void setExtractionMaxPromptChars(int extractionMaxPromptChars) {
        this.extractionMaxPromptChars = Math.max(256, extractionMaxPromptChars);
    }

    public int getExtractionMaxFacts() {
        return Math.max(1, extractionMaxFacts);
    }

    public void setExtractionMaxFacts(int extractionMaxFacts) {
        this.extractionMaxFacts = Math.max(1, extractionMaxFacts);
    }
}
