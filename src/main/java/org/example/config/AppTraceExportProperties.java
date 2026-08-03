package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Optional, bounded HTTP trace export settings. */
@Component
@ConfigurationProperties(prefix = "app.trace-export")
public class AppTraceExportProperties {

    private boolean enabled;
    private String endpoint = "";
    private String apiKey = "";
    private String serviceName = "superbizagent";
    private double samplingProbability = 0.1d;
    private long timeoutMillis = 1000L;
    private int maxPending = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public double getSamplingProbability() {
        return samplingProbability;
    }

    public void setSamplingProbability(double samplingProbability) {
        this.samplingProbability = samplingProbability;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getMaxPending() {
        return maxPending;
    }

    public void setMaxPending(int maxPending) {
        this.maxPending = maxPending;
    }
}
