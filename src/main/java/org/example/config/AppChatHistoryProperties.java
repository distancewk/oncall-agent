package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.chat-history")
public class AppChatHistoryProperties {

    private String path = "./data/chat-history";

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
