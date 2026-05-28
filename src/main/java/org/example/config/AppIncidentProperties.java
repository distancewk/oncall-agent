package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.incidents")
public class AppIncidentProperties {

    private String path = "./data/incidents";
    private boolean diagnosisReuseEnabled = true;
    private long diagnosisReuseWindowMillis = 3_600_000L;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isDiagnosisReuseEnabled() {
        return diagnosisReuseEnabled;
    }

    public void setDiagnosisReuseEnabled(boolean diagnosisReuseEnabled) {
        this.diagnosisReuseEnabled = diagnosisReuseEnabled;
    }

    public long getDiagnosisReuseWindowMillis() {
        return diagnosisReuseWindowMillis;
    }

    public void setDiagnosisReuseWindowMillis(long diagnosisReuseWindowMillis) {
        this.diagnosisReuseWindowMillis = diagnosisReuseWindowMillis;
    }
}
