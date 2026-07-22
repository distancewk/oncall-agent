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
        ReflectionTestUtils.setField(aiOpsService, "agentToolSurfaceService",
                new AgentToolSurfaceService(
                        dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools, null));
    }

    @Test
    void extractFinalReport_shouldReturnEmpty_whenStateDoesNotContainPlannerPlan() {
        OverAllState state = new OverAllState();
        Optional<String> report = aiOpsService.extractFinalReport(state);
        assertTrue(report.isEmpty());
    }

    @Test
    void extractFinalReport_shouldReturnReport_whenStateContainsPlannerPlan() {
        String expectedReport = validReport();
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
    void extractFinalReport_shouldRejectPlannerPlanJson_evenWhenValidationTextFollows() {
        String intermediate = """
                {"decision":"PLAN","step":"补充 GC 日志证据","tool":"queryLogs"}

                ## 证据校验
                校验状态: 通过
                """;
        OverAllState state = new OverAllState(Map.of("planner_plan", new AssistantMessage(intermediate)));

        Optional<String> report = aiOpsService.extractFinalReport(state);

        assertTrue(report.isEmpty());
    }

    @Test
    void extractFinalReport_shouldKeepMarkdownReportWithoutDecisionField() {
        String expectedReport = validReport();
        OverAllState state = new OverAllState(Map.of(
                "planner_plan", new AssistantMessage(expectedReport)));

        Optional<String> report = aiOpsService.extractFinalReport(state);

        assertEquals(Optional.of(expectedReport), report);
    }

    @Test
    void extractFinalReport_shouldPreferFinalReportOverIntermediatePlannerPlan() {
        String expectedReport = validReport();
        OverAllState state = new OverAllState(Map.of(
                "planner_plan", new AssistantMessage("{\"decision\":\"PLAN\",\"step\":\"queryLogs\"}"),
                "final_report", new AssistantMessage(expectedReport)));

        Optional<String> report = aiOpsService.extractFinalReport(state);

        assertEquals(Optional.of(expectedReport), report);
    }

    @Test
    void extractFinalReport_shouldRejectPlainIntermediateTextAndToolLimitMessage() {
        assertTrue(aiOpsService.extractFinalReport(new OverAllState(Map.of(
                "planner_plan", new AssistantMessage("请先补充 GC 日志证据")))).isEmpty());
        assertTrue(aiOpsService.extractFinalReport(new OverAllState(Map.of(
                "planner_plan", new AssistantMessage("Tool call limit exceeded")))).isEmpty());
        assertTrue(aiOpsService.extractFinalReport(new OverAllState(Map.of(
                "planner_plan", new AssistantMessage("{\"decision\":\"FINISH\"}")))).isEmpty());
    }

    private String validReport() {
        return """
                # 告警分析报告

                ## 活跃告警清单
                | 告警名称 | 状态 |
                |---|---|
                | HighCPUUsage | 活跃 |

                ## 告警根因分析1
                CPU 指标异常，证据不足。

                ## 处理方案执行1
                建议继续观察。

                ## 结论
                当前无法完成进一步确认。

                ### 置信度
                低

                ### 缺失证据
                - GC 日志
                """;
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
    void prompts_shouldReuseMatchingPrefetchedMetricTrendEvidence() {
        String plannerPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildPlannerPrompt");
        String executorPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildExecutorPrompt");

        assertNotNull(plannerPrompt);
        assertNotNull(executorPrompt);
        assertTrue(plannerPrompt.contains("已有成功的 queryMetricTrend evidence"));
        assertTrue(plannerPrompt.contains("metric、service、instance、window 与当前诊断目标匹配"));
        assertTrue(plannerPrompt.contains("15m/1h/6h"));
        assertTrue(executorPrompt.contains("已有成功的 queryMetricTrend evidence"));
        assertTrue(executorPrompt.contains("优先复用"));
        assertTrue(executorPrompt.contains("metric、service、instance、window 与当前诊断目标匹配"));
        assertTrue(executorPrompt.contains("缺失 15m/1h/6h 中最相关窗口或查询失败时才再次调用"));
        assertFalse(executorPrompt.contains("必须优先调用 queryMetricTrend"));
    }

    @Test
    void buildPlannerMethodToolsArray_shouldBeEmpty_soExecutorOwnsDiagnosticExecution() {
        Object[] methodTools = ReflectionTestUtils.invokeMethod(aiOpsService, "buildPlannerMethodToolsArray");

        assertNotNull(methodTools);
        assertEquals(0, methodTools.length);
    }

    @Test
    void buildExecutorMethodToolsArray_shouldKeepDiagnosisToolsForAiOps() {
        Object[] methodTools = ReflectionTestUtils.invokeMethod(aiOpsService, "buildExecutorMethodToolsArray");

        assertNotNull(methodTools);
        assertEquals(4, methodTools.length);
        assertSame(dateTimeTools, methodTools[0]);
        assertSame(internalDocsTools, methodTools[1]);
        assertSame(queryMetricsTools, methodTools[2]);
        assertSame(queryLogsTools, methodTools[3]);
    }

    @Test
    void buildExecutorToolCallHooks_shouldLimitTotalAndQueryLogsAttempts() {
        org.example.config.AppIncidentProperties properties = new org.example.config.AppIncidentProperties();
        properties.setMaxToolAttemptsPerRun(16);
        properties.setQueryLogsMaxAttemptsPerRun(5);
        ReflectionTestUtils.setField(aiOpsService, "incidentProperties", properties);

        Object[] hooks = ReflectionTestUtils.invokeMethod(aiOpsService, "buildExecutorToolCallHooks");

        assertNotNull(hooks);
        assertEquals(2, hooks.length);
        assertTrue(hooks[0] instanceof com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook);
        assertTrue(hooks[1] instanceof com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook);
    }

    @Test
    void supervisorRecursionLimit_shouldScaleWithConfiguredRounds() {
        org.example.config.AppIncidentProperties properties = new org.example.config.AppIncidentProperties();
        properties.setMaxSupervisorRounds(8);
        ReflectionTestUtils.setField(aiOpsService, "incidentProperties", properties);

        assertEquals(36, ((Integer) ReflectionTestUtils.invokeMethod(
                aiOpsService, "supervisorRecursionLimit")).intValue());
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
    void finalReportPrompt_shouldRequireMarkdownAndRejectFabricatedEvidence() {
        String prompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildFinalReportPrompt");

        assertNotNull(prompt);
        assertTrue(prompt.contains("只能使用输入中已有的告警上下文"));
        assertTrue(prompt.contains("# 告警分析报告"));
        assertTrue(prompt.contains("### 置信度"));
        assertTrue(prompt.contains("### 缺失证据"));
        assertTrue(prompt.contains("不要输出 JSON"));
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
    void prompts_shouldDescribeLogToolBudgetAndDeduplication() {
        String plannerPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildPlannerPrompt");
        String executorPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildExecutorPrompt");

        assertNotNull(plannerPrompt);
        assertNotNull(executorPrompt);
        assertTrue(plannerPrompt.contains("工具调用总次数默认最多 12 次"));
        assertTrue(plannerPrompt.contains("queryLogs 实际调用最多 3 次、尝试最多 5 次"));
        assertTrue(plannerPrompt.contains("模型工具调用尝试默认最多 16 次"));
        assertTrue(plannerPrompt.contains("同一 toolName + 同一参数或等价参数禁止重复调用"));
        assertTrue(executorPrompt.contains("TOOL_BUDGET_EXCEEDED"));
        assertTrue(executorPrompt.contains("TOOL_DUPLICATE_SKIPPED"));
        assertTrue(executorPrompt.contains("queryLogs 实际调用最多 3 次、尝试最多 5 次"));
    }

    @Test
    void plannerPrompt_shouldAssignAllToolExecutionToExecutor() {
        String plannerPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildPlannerPrompt");

        assertTrue(plannerPrompt.contains("Planner 只负责规划和再规划，严禁直接调用诊断工具"));
        assertTrue(plannerPrompt.contains("所有工具调用必须交给 Executor"));
    }

    @Test
    void prompts_shouldConstrainTavilyAndDatabaseMcpTools() {
        String plannerPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildPlannerPrompt");
        String executorPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildExecutorPrompt");
        String supervisorPrompt = ReflectionTestUtils.invokeMethod(aiOpsService, "buildSupervisorSystemPrompt");

        assertNotNull(plannerPrompt);
        assertNotNull(executorPrompt);
        assertTrue(plannerPrompt.contains("Tavily MCP"));
        assertTrue(plannerPrompt.contains("数据库 MCP"));
        assertTrue(plannerPrompt.contains("只读"));
        assertTrue(executorPrompt.contains("禁止执行 INSERT"));
        assertTrue(executorPrompt.contains("UPDATE / DELETE / DROP / ALTER / TRUNCATE"));
        assertTrue(supervisorPrompt.contains("# 告警分析报告"));
        assertTrue(supervisorPrompt.contains("置信度"));
        assertTrue(supervisorPrompt.contains("缺失证据"));
    }
}
