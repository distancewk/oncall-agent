package org.example.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Configuration for explicit, operator-triggered dependency connectivity probes. */
@Component
@Validated
@ConfigurationProperties(prefix = "app.dependency-probes")
public class AppDependencyProbeProperties {

    private boolean enabled = true;
    @Min(100)
    @Max(10_000)
    private int timeoutMillis = 2_000;
    private String prometheusUrl = "http://localhost:9090";
    private String prometheusApiKey = "";
    private String clsUrl = "";
    private String clsApiKey = "";
    private boolean clsNativeSigningEnabled;
    private String clsSecretId = "";
    private String clsSecretKey = "";
    private String clsRegion = "ap-guangzhou";
    private String clsService = "cls";
    private String clsProbePath = "/";
    private String clsProbeTopicId = "";
    private String dashscopeUrl = "https://dashscope.aliyuncs.com/api/v1";
    private String dashscopeApiKey = "";
    private String dashscopeProbePath =
            "/deployments/models?page_no=1&page_size=1&version=v1.0&model_source=base";
    private String milvusHost = "localhost";
    private int milvusPort = 19530;
    private boolean mcpEnabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public String getPrometheusUrl() {
        return prometheusUrl;
    }

    public void setPrometheusUrl(String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    public String getPrometheusApiKey() {
        return prometheusApiKey;
    }

    public void setPrometheusApiKey(String prometheusApiKey) {
        this.prometheusApiKey = prometheusApiKey;
    }

    public String getClsUrl() {
        return clsUrl;
    }

    public void setClsUrl(String clsUrl) {
        this.clsUrl = clsUrl;
    }

    public String getClsApiKey() {
        return clsApiKey;
    }

    public void setClsApiKey(String clsApiKey) {
        this.clsApiKey = clsApiKey;
    }

    public boolean isClsNativeSigningEnabled() {
        return clsNativeSigningEnabled;
    }

    public void setClsNativeSigningEnabled(boolean clsNativeSigningEnabled) {
        this.clsNativeSigningEnabled = clsNativeSigningEnabled;
    }

    public String getClsSecretId() {
        return clsSecretId;
    }

    public void setClsSecretId(String clsSecretId) {
        this.clsSecretId = clsSecretId;
    }

    public String getClsSecretKey() {
        return clsSecretKey;
    }

    public void setClsSecretKey(String clsSecretKey) {
        this.clsSecretKey = clsSecretKey;
    }

    public String getClsRegion() {
        return clsRegion;
    }

    public void setClsRegion(String clsRegion) {
        this.clsRegion = clsRegion;
    }

    public String getClsService() {
        return clsService;
    }

    public void setClsService(String clsService) {
        this.clsService = clsService;
    }

    public String getClsProbePath() {
        return clsProbePath;
    }

    public void setClsProbePath(String clsProbePath) {
        this.clsProbePath = clsProbePath;
    }

    public String getClsProbeTopicId() {
        return clsProbeTopicId;
    }

    public void setClsProbeTopicId(String clsProbeTopicId) {
        this.clsProbeTopicId = clsProbeTopicId;
    }

    public String getDashscopeUrl() {
        return dashscopeUrl;
    }

    public void setDashscopeUrl(String dashscopeUrl) {
        this.dashscopeUrl = dashscopeUrl;
    }

    public String getDashscopeApiKey() {
        return dashscopeApiKey;
    }

    public void setDashscopeApiKey(String dashscopeApiKey) {
        this.dashscopeApiKey = dashscopeApiKey;
    }

    public String getDashscopeProbePath() {
        return dashscopeProbePath;
    }

    public void setDashscopeProbePath(String dashscopeProbePath) {
        this.dashscopeProbePath = dashscopeProbePath;
    }

    public String getMilvusHost() {
        return milvusHost;
    }

    public void setMilvusHost(String milvusHost) {
        this.milvusHost = milvusHost;
    }

    public int getMilvusPort() {
        return milvusPort;
    }

    public void setMilvusPort(int milvusPort) {
        this.milvusPort = milvusPort;
    }

    public boolean isMcpEnabled() {
        return mcpEnabled;
    }

    public void setMcpEnabled(boolean mcpEnabled) {
        this.mcpEnabled = mcpEnabled;
    }
}
