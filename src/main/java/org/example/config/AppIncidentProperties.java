package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.incidents")
public class AppIncidentProperties {

    private String path = "./data/incidents";

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
