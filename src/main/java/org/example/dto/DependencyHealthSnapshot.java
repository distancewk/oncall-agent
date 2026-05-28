package org.example.dto;

public class DependencyHealthSnapshot {
    private String name;
    private String state;
    private float failureRate;
    private float slowCallRate;
    private int bufferedCalls;
    private String lastError;
    private Long openedAt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public float getFailureRate() {
        return failureRate;
    }

    public void setFailureRate(float failureRate) {
        this.failureRate = failureRate;
    }

    public float getSlowCallRate() {
        return slowCallRate;
    }

    public void setSlowCallRate(float slowCallRate) {
        this.slowCallRate = slowCallRate;
    }

    public int getBufferedCalls() {
        return bufferedCalls;
    }

    public void setBufferedCalls(int bufferedCalls) {
        this.bufferedCalls = bufferedCalls;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Long getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Long openedAt) {
        this.openedAt = openedAt;
    }
}
