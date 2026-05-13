package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.memory")
public class AppMemoryProperties {

    private boolean privateRecallEnabled = true;
    private int privateRecallTopK = 3;

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
}
