package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.incidents")
public class AppIncidentProperties {

    private String path = "./data/incidents";
    private String jdbcUrl = "jdbc:postgresql://localhost:5432/superbizagent";
    private String jdbcUsername = "superbizagent";
    private String jdbcPassword = "superbizagent";
    private String jdbcDriverClassName = "";
    private int jdbcMaximumPoolSize = 10;
    private int jdbcMinimumIdle = 1;
    private long jdbcConnectionTimeoutMillis = 5_000L;
    private long jdbcInitializationFailTimeoutMillis = -1L;
    private boolean diagnosisReuseEnabled = true;
    private long diagnosisReuseWindowMillis = 3_600_000L;
    private boolean toolCallDeduplicationEnabled = true;
    private int queryLogsMaxCallsPerRun = 3;
    private int queryInternalDocsMaxCallsPerRun = 3;
    private int tavilyMaxCallsPerRun = 2;
    private int dbhubMaxCallsPerRun = 2;
    private int maxToolCallsPerRun = 12;
    private long staleRunTimeoutMillis = 600_000L;
    private long staleRunSweepDelayMillis = 60_000L;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getJdbcUsername() {
        return jdbcUsername;
    }

    public void setJdbcUsername(String jdbcUsername) {
        this.jdbcUsername = jdbcUsername;
    }

    public String getJdbcPassword() {
        return jdbcPassword;
    }

    public void setJdbcPassword(String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
    }

    public String getJdbcDriverClassName() {
        return jdbcDriverClassName;
    }

    public void setJdbcDriverClassName(String jdbcDriverClassName) {
        this.jdbcDriverClassName = jdbcDriverClassName;
    }

    public int getJdbcMaximumPoolSize() {
        return jdbcMaximumPoolSize;
    }

    public void setJdbcMaximumPoolSize(int jdbcMaximumPoolSize) {
        this.jdbcMaximumPoolSize = jdbcMaximumPoolSize;
    }

    public int getJdbcMinimumIdle() {
        return jdbcMinimumIdle;
    }

    public void setJdbcMinimumIdle(int jdbcMinimumIdle) {
        this.jdbcMinimumIdle = jdbcMinimumIdle;
    }

    public long getJdbcConnectionTimeoutMillis() {
        return jdbcConnectionTimeoutMillis;
    }

    public void setJdbcConnectionTimeoutMillis(long jdbcConnectionTimeoutMillis) {
        this.jdbcConnectionTimeoutMillis = jdbcConnectionTimeoutMillis;
    }

    public long getJdbcInitializationFailTimeoutMillis() {
        return jdbcInitializationFailTimeoutMillis;
    }

    public void setJdbcInitializationFailTimeoutMillis(long jdbcInitializationFailTimeoutMillis) {
        this.jdbcInitializationFailTimeoutMillis = jdbcInitializationFailTimeoutMillis;
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

    public boolean isToolCallDeduplicationEnabled() {
        return toolCallDeduplicationEnabled;
    }

    public void setToolCallDeduplicationEnabled(boolean toolCallDeduplicationEnabled) {
        this.toolCallDeduplicationEnabled = toolCallDeduplicationEnabled;
    }

    public int getQueryLogsMaxCallsPerRun() {
        return queryLogsMaxCallsPerRun;
    }

    public void setQueryLogsMaxCallsPerRun(int queryLogsMaxCallsPerRun) {
        this.queryLogsMaxCallsPerRun = queryLogsMaxCallsPerRun;
    }

    public int getQueryInternalDocsMaxCallsPerRun() {
        return queryInternalDocsMaxCallsPerRun;
    }

    public void setQueryInternalDocsMaxCallsPerRun(int queryInternalDocsMaxCallsPerRun) {
        this.queryInternalDocsMaxCallsPerRun = queryInternalDocsMaxCallsPerRun;
    }

    public int getTavilyMaxCallsPerRun() {
        return tavilyMaxCallsPerRun;
    }

    public void setTavilyMaxCallsPerRun(int tavilyMaxCallsPerRun) {
        this.tavilyMaxCallsPerRun = tavilyMaxCallsPerRun;
    }

    public int getDbhubMaxCallsPerRun() {
        return dbhubMaxCallsPerRun;
    }

    public void setDbhubMaxCallsPerRun(int dbhubMaxCallsPerRun) {
        this.dbhubMaxCallsPerRun = dbhubMaxCallsPerRun;
    }

    public int getMaxToolCallsPerRun() {
        return maxToolCallsPerRun;
    }

    public void setMaxToolCallsPerRun(int maxToolCallsPerRun) {
        this.maxToolCallsPerRun = maxToolCallsPerRun;
    }

    public long getStaleRunTimeoutMillis() {
        return staleRunTimeoutMillis;
    }

    public void setStaleRunTimeoutMillis(long staleRunTimeoutMillis) {
        this.staleRunTimeoutMillis = staleRunTimeoutMillis;
    }

    public long getStaleRunSweepDelayMillis() {
        return staleRunSweepDelayMillis;
    }

    public void setStaleRunSweepDelayMillis(long staleRunSweepDelayMillis) {
        this.staleRunSweepDelayMillis = staleRunSweepDelayMillis;
    }
}
