package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.lifecycle")
public class AppDataLifecycleProperties {

    private boolean enabled;
    private int terminalJobRetentionDays;
    private int indexTaskRetentionDays;
    private int chatSessionRetentionDays;
    private int securityAuditRetentionDays;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTerminalJobRetentionDays() {
        return terminalJobRetentionDays;
    }

    public void setTerminalJobRetentionDays(int terminalJobRetentionDays) {
        this.terminalJobRetentionDays = terminalJobRetentionDays;
    }

    public int getIndexTaskRetentionDays() {
        return indexTaskRetentionDays;
    }

    public void setIndexTaskRetentionDays(int indexTaskRetentionDays) {
        this.indexTaskRetentionDays = indexTaskRetentionDays;
    }

    public int getChatSessionRetentionDays() {
        return chatSessionRetentionDays;
    }

    public void setChatSessionRetentionDays(int chatSessionRetentionDays) {
        this.chatSessionRetentionDays = chatSessionRetentionDays;
    }

    public int getSecurityAuditRetentionDays() {
        return securityAuditRetentionDays;
    }

    public void setSecurityAuditRetentionDays(int securityAuditRetentionDays) {
        this.securityAuditRetentionDays = securityAuditRetentionDays;
    }
}
