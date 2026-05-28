package org.example.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AiOpsServiceTest {

    private AiOpsService aiOpsService;

    @Mock
    private org.example.agent.tool.DateTimeTools dateTimeTools;

    @Mock
    private org.example.agent.tool.InternalDocsTools internalDocsTools;

    @Mock
    private org.example.agent.tool.QueryMetricsTools queryMetricsTools;

    @Mock
    private org.example.agent.tool.QueryLogsTools queryLogsTools;

    @BeforeEach
    void setUp() {
        aiOpsService = new AiOpsService();
        ReflectionTestUtils.setField(aiOpsService, "dateTimeTools", dateTimeTools);
        ReflectionTestUtils.setField(aiOpsService, "internalDocsTools", internalDocsTools);
        ReflectionTestUtils.setField(aiOpsService, "queryMetricsTools", queryMetricsTools);
        ReflectionTestUtils.setField(aiOpsService, "queryLogsTools", queryLogsTools);
    }

    @Test
    void extractFinalReport_shouldReturnEmpty_whenStateDoesNotContainPlannerPlan() {
        OverAllState state = new OverAllState();
        Optional<String> report = aiOpsService.extractFinalReport(state);
        assertTrue(report.isEmpty());
    }

    @Test
    void extractFinalReport_shouldReturnReport_whenStateContainsPlannerPlan() {
        String expectedReport = "# 告警分析报告\n\n测试报告内容";
        AssistantMessage message = new AssistantMessage(expectedReport);
        OverAllState state = new OverAllState(Map.of("planner_plan", message));

        Optional<String> report = aiOpsService.extractFinalReport(state);
        assertTrue(report.isPresent());
        assertEquals(expectedReport, report.get());
    }

    @Test
    void extractFinalReport_shouldReturnEmpty_whenPlannerPlanIsNotAssistantMessage() {
        OverAllState state = new OverAllState(Map.of("planner_plan", "just a string"));

        Optional<String> report = aiOpsService.extractFinalReport(state);
        assertTrue(report.isEmpty());
    }

    @Test
    void prompts_shouldRequireMetricTrendEvidenceForResourceDiagnosis() {
        String plannerPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildPlannerPrompt");
        String executorPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildExecutorPrompt");

        assertNotNull(plannerPrompt);
        assertNotNull(executorPrompt);
        assertTrue(plannerPrompt.contains("queryMetricTrend"));
        assertTrue(executorPrompt.contains("queryMetricTrend"));
        assertTrue(plannerPrompt.contains("趋势"));
        assertTrue(executorPrompt.contains("趋势"));
    }

    @Test
    void buildMethodToolsArray_shouldKeepDiagnosisToolsForAiOps() {
        Object[] methodTools = ReflectionTestUtils.invokeMethod(aiOpsService, "buildMethodToolsArray");

        assertNotNull(methodTools);
        assertEquals(4, methodTools.length);
        assertSame(dateTimeTools, methodTools[0]);
        assertSame(internalDocsTools, methodTools[1]);
        assertSame(queryMetricsTools, methodTools[2]);
        assertSame(queryLogsTools, methodTools[3]);
    }

    @Test
    void plannerPrompt_shouldRequireEvidenceBoundConfidenceAndMissingEvidence() {
        String plannerPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildPlannerPrompt");

        assertNotNull(plannerPrompt);
        assertTrue(plannerPrompt.contains("证据不足"));
        assertTrue(plannerPrompt.contains("置信度"));
        assertTrue(plannerPrompt.contains("缺失证据"));
        assertTrue(plannerPrompt.contains("evidence id"));
    }

    @Test
    void prompts_shouldTreatActiveAlertQueryAsConditionalWhenIncidentContextExists() {
        String plannerPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildPlannerPrompt");
        String executorPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildExecutorPrompt");

        assertNotNull(plannerPrompt);
        assertNotNull(executorPrompt);
        assertTrue(plannerPrompt.contains("当前 Incident 告警上下文是首要事实源"));
        assertTrue(plannerPrompt.contains("queryPrometheusAlerts 是条件工具"));
        assertTrue(plannerPrompt.contains("不要把 queryPrometheusAlerts 作为默认第一步"));
        assertTrue(executorPrompt.contains("已有明确告警上下文时，不要重复查询活动告警"));
    }

    @Test
    void prompts_shouldOnlyDiscoverLogTopicsWhenTopicCannotBeInferred() {
        String plannerPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildPlannerPrompt");
        String executorPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildExecutorPrompt");

        assertNotNull(plannerPrompt);
        assertNotNull(executorPrompt);
        assertTrue(plannerPrompt.contains("只有无法根据告警类型推断日志主题时，才调用 getAvailableLogTopics"));
        assertTrue(executorPrompt.contains("能从告警类型推断日志主题时，直接调用 queryLogs"));
    }

    @Test
    void prompts_shouldConstrainTavilyAndDatabaseMcpTools() {
        String plannerPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildPlannerPrompt");
        String executorPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildExecutorPrompt");

        assertNotNull(plannerPrompt);
        assertNotNull(executorPrompt);
        assertTrue(plannerPrompt.contains("Tavily MCP"));
        assertTrue(plannerPrompt.contains("数据库 MCP"));
        assertTrue(plannerPrompt.contains("只读"));
        assertTrue(executorPrompt.contains("禁止执行 INSERT"));
        assertTrue(executorPrompt.contains("UPDATE / DELETE / DROP / ALTER / TRUNCATE"));
    }
}
