package org.example.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "app.jobs")
public class AppJobProperties {

    private boolean enabled = true;
    @Positive(message = "pollDelayMillis must be greater than 0")
    private long pollDelayMillis = 1_000L;
    @Positive(message = "leaseDurationMillis must be greater than 0")
    private long leaseDurationMillis = 60_000L;
    @Positive(message = "heartbeatIntervalMillis must be greater than 0")
    private long heartbeatIntervalMillis = 15_000L;
    @Positive(message = "recoveryDelayMillis must be greater than 0")
    private long recoveryDelayMillis = 15_000L;
    @Positive(message = "workerConcurrency must be greater than 0")
    private int workerConcurrency = 4;
    @Positive(message = "diagnosisMaxAttempts must be greater than 0")
    private int diagnosisMaxAttempts = 2;
    @Positive(message = "indexMaxAttempts must be greater than 0")
    private int indexMaxAttempts = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollDelayMillis() {
        return pollDelayMillis;
    }

    public void setPollDelayMillis(long pollDelayMillis) {
        this.pollDelayMillis = pollDelayMillis;
    }

    public long getLeaseDurationMillis() {
        return leaseDurationMillis;
    }

    public void setLeaseDurationMillis(long leaseDurationMillis) {
        this.leaseDurationMillis = leaseDurationMillis;
    }

    public long getHeartbeatIntervalMillis() {
        return heartbeatIntervalMillis;
    }

    public void setHeartbeatIntervalMillis(long heartbeatIntervalMillis) {
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
    }

    public long getRecoveryDelayMillis() {
        return recoveryDelayMillis;
    }

    public void setRecoveryDelayMillis(long recoveryDelayMillis) {
        this.recoveryDelayMillis = recoveryDelayMillis;
    }

    public int getWorkerConcurrency() {
        return workerConcurrency;
    }

    public void setWorkerConcurrency(int workerConcurrency) {
        this.workerConcurrency = workerConcurrency;
    }

    public int getDiagnosisMaxAttempts() {
        return diagnosisMaxAttempts;
    }

    public void setDiagnosisMaxAttempts(int diagnosisMaxAttempts) {
        this.diagnosisMaxAttempts = diagnosisMaxAttempts;
    }

    public int getIndexMaxAttempts() {
        return indexMaxAttempts;
    }

    public void setIndexMaxAttempts(int indexMaxAttempts) {
        this.indexMaxAttempts = indexMaxAttempts;
    }

    @AssertTrue(message = "heartbeatIntervalMillis must be less than half of leaseDurationMillis")
    public boolean isHeartbeatIntervalLessThanHalfLease() {
        return heartbeatIntervalMillis < leaseDurationMillis / 2L;
    }
}
