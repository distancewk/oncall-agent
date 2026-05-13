package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.cors")
public class AppCorsProperties {

    private String allowedOrigins = "http://localhost:9900,http://127.0.0.1:9900";

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public String[] allowedOriginArray() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return new String[]{"http://localhost:9900", "http://127.0.0.1:9900"};
        }
        return java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }
}
