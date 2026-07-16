package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.chat")
public class AppChatProperties {

    private boolean directModelRoutingEnabled = true;

    public boolean isDirectModelRoutingEnabled() {
        return directModelRoutingEnabled;
    }

    public void setDirectModelRoutingEnabled(boolean directModelRoutingEnabled) {
        this.directModelRoutingEnabled = directModelRoutingEnabled;
    }
}
