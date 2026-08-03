package org.example.service;

import org.example.agent.tool.MetricCatalog;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 可审计的 AI Ops 提示词目录。
 *
 * <p>提示词是运行时行为的一部分，必须与代码一样可版本控制、可测试，且不能
 * 因为资源缺失而在首次告警发生时才失败。</p>
 */
@Component
public final class AiOpsPromptCatalog {

    public static final String PLANNER_VERSION = "planner-v1";
    public static final String EXECUTOR_VERSION = "executor-v1";
    public static final String FINAL_REPORT_VERSION = "final-report-v1";
    public static final String FINALIZATION_VERSION = "finalization-v1";

    private static final String PLANNER_RESOURCE = "prompts/aiops-planner-system-prompt.txt";
    private static final String EXECUTOR_RESOURCE = "prompts/aiops-executor-system-prompt.txt";
    private static final String FINAL_REPORT_RESOURCE = "prompts/aiops-final-report-system-prompt.txt";
    private static final String FINALIZATION_RESOURCE = "prompts/aiops-finalization-prompt.txt";

    public AiOpsPromptCatalog() {
        validate();
    }

    public String plannerPrompt() {
        return load(PLANNER_RESOURCE).replace("{{SUPPORTED_METRICS}}", MetricCatalog.supportedNamesText());
    }

    public String executorPrompt() {
        return load(EXECUTOR_RESOURCE).replace("{{SUPPORTED_METRICS}}", MetricCatalog.supportedNamesText());
    }

    public String finalReportPrompt() {
        return load(FINAL_REPORT_RESOURCE);
    }

    public String finalizationPrompt() {
        return load(FINALIZATION_RESOURCE);
    }

    public String versionSummary() {
        return "planner=" + PLANNER_VERSION
                + ",executor=" + EXECUTOR_VERSION
                + ",finalReport=" + FINAL_REPORT_VERSION
                + ",finalization=" + FINALIZATION_VERSION;
    }

    void validate() {
        validateTemplate(PLANNER_RESOURCE, PLANNER_VERSION, "{{SUPPORTED_METRICS}}");
        validateTemplate(EXECUTOR_RESOURCE, EXECUTOR_VERSION, "{{SUPPORTED_METRICS}}");
        validateTemplate(FINAL_REPORT_RESOURCE, FINAL_REPORT_VERSION);
        validateTemplate(FINALIZATION_RESOURCE, FINALIZATION_VERSION,
                "## 告警上下文", "## 代码侧确定性 Runbook", "## 当前已持久化证据表");
    }

    private void validateTemplate(String resource, String version, String... requiredTokens) {
        String content = load(resource);
        if (content.isBlank()) {
            throw new IllegalStateException("提示词资源为空: " + resource);
        }
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("提示词版本未配置: " + resource);
        }
        for (String requiredToken : requiredTokens) {
            if (!content.contains(requiredToken)) {
                throw new IllegalStateException("提示词资源缺少占位符 " + requiredToken + ": " + resource);
            }
        }
    }

    private String load(String resource) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("找不到提示词资源: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词资源失败: " + resource, e);
        }
    }
}
