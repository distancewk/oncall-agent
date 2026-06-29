package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.memory")
public class AppMemoryProperties {

    private static final int MIN_PRIVATE_RECALL_PROMPT_CHARS = 1;

    private boolean privateRecallEnabled = true;
    private int privateRecallTopK = 3;
    private float privateRecallMinScore = 0.0f;
    private int privateRecallMaxPromptChars = 1200;

    public boolean isPrivateRecallEnabled() {
        return privateRecallEnabled;
    }

    public void setPrivateRecallEnabled(boolean privateRecallEnabled) {
        this.privateRecallEnabled = privateRecallEnabled;
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
}
