package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import org.example.config.AppIncidentProperties;
import org.example.config.AgentObservationHandler;
import org.example.dto.DiagnosisRunRecord;
import org.example.exception.DependencyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;

/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的告警分析流程
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);
    private static final Pattern DECISION_PATTERN = Pattern.compile(
            "\\\"decision\\\"\\s*:\\s*\\\"(PLAN|EXECUTE|FINISH)\\\"",
            Pattern.CASE_INSENSITIVE);

    @Autowired
    private AgentToolSurfaceService agentToolSurfaceService;

    @Autowired(required = false)
    private DiagnosisEvidenceRecorder diagnosisEvidenceRecorder;

    @Autowired(required = false)
    private DiagnosisReportService diagnosisReportService;

    @Autowired(required = false)
    private IncidentService incidentService;

    @Autowired(required = false)
    private DependencyGuard dependencyGuard;

    @Autowired(required = false)
    private AppIncidentProperties incidentProperties;

    @Autowired(required = false)
    private ObservabilityMetrics observabilityMetrics;

    private final AiOpsPromptCatalog promptCatalog;
    private final DiagnosisOrchestrationPolicy orchestrationPolicy = new DiagnosisOrchestrationPolicy();
    private final DiagnosisRunbookPolicy runbookPolicy = new DiagnosisRunbookPolicy();

    public AiOpsService() {
        this(new AiOpsPromptCatalog());
    }

    @Autowired
    public AiOpsService(AiOpsPromptCatalog promptCatalog) {
        this.promptCatalog = Objects.requireNonNull(promptCatalog, "promptCatalog");
    }

    public String promptVersionSummary() {
        return promptCatalog.versionSummary();
    }

    /**
     * 执行 AI Ops 告警分析流程（向后兼容，无告警上下文）
     */
    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        return executeAiOpsAnalysis(chatModel, toolCallbacks, null);
    }

    /**
     * 执行 AI Ops 告警分析流程
     *
     * @param chatModel      大模型实例
     * @param toolCallbacks  工具回调数组
     * @param alertContext   告警上下文信息（可为空）
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks, String alertContext) throws GraphRunnerException {
        return executeAiOpsAnalysisInternal(chatModel, toolCallbacks, alertContext, false);
    }

    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel,
                                                       ToolCallback[] toolCallbacks,
                                                       String alertContext,
                                                       String incidentId,
                                                       String runId) throws GraphRunnerException {
        String evidenceBoundContext = buildEvidenceBoundAlertContext(alertContext, incidentId, runId);
        if (diagnosisEvidenceRecorder == null || incidentId == null || incidentId.isBlank()
                || runId == null || runId.isBlank()) {
            return executeAiOpsAnalysisInternal(chatModel, toolCallbacks, evidenceBoundContext, false);
        }
        try {
            return diagnosisEvidenceRecorder.withRun(incidentId, runId,
                    () -> executeAiOpsAnalysisInternal(chatModel, toolCallbacks, evidenceBoundContext, true));
        } catch (GraphRunnerException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphRunnerException("执行 AI Ops 诊断证据记录流程失败", e);
        }
    }

    private String buildEvidenceBoundAlertContext(String alertContext, String incidentId, String runId) {
        if (diagnosisReportService == null || incidentService == null
                || incidentId == null || incidentId.isBlank()
                || runId == null || runId.isBlank()) {
            return alertContext;
        }
        try {
            return incidentService.getDiagnosisRuns(incidentId)
                    .flatMap(runs -> runs.stream()
                            .filter(run -> runId.equals(run.getRunId()))
                            .findFirst())
                    .map(DiagnosisRunRecord::getEvidence)
                    .map(evidence -> diagnosisReportService.augmentAlertContext(alertContext, evidence))
                    .orElse(alertContext);
        } catch (Exception e) {
            logger.warn("构建证据约束上下文失败，将使用原始告警上下文, incidentId: {}, runId: {}",
                    incidentId, runId, e);
            return alertContext;
        }
    }

    private Optional<OverAllState> executeAiOpsAnalysisInternal(DashScopeChatModel chatModel,
                                                               ToolCallback[] toolCallbacks,
                                                               String alertContext,
                                                               boolean recordToolEvidence)
            throws GraphRunnerException {
        logger.info("开始执行 AI Ops 多 Agent 协作流程, promptVersions={}",
                promptCatalog.versionSummary());

        ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks, recordToolEvidence);
        ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks, recordToolEvidence);

        String taskPrompt = "你是企业级 SRE，接到了自动化告警排查任务。请结合工具调用，执行**规划→执行→再规划**的闭环，并最终按照固定模板输出《告警分析报告》。禁止编造虚假数据，如连续多次查询失败需诚实反馈无法完成的原因。报告结论必须绑定 evidence id；证据不足时必须显式说明缺失证据。";

        if (alertContext != null && !alertContext.isEmpty()) {
            taskPrompt += "\n\n## 当前告警上下文\n" + alertContext + "\n\n请基于以上告警上下文进行分析和处理。";
            logger.info("已注入告警上下文，长度: {}", alertContext.length());
        }
        taskPrompt += "\n\n" + runbookPolicy.promptFor(alertContext);

        logger.info("调用代码侧编排器开始执行 Planner/Executor 状态机...");
        return executeOrchestration(plannerAgent, executorAgent, chatModel, taskPrompt, alertContext);
    }

    private Optional<OverAllState> executeOrchestration(ReactAgent plannerAgent,
                                                        ReactAgent executorAgent,
                                                        DashScopeChatModel chatModel,
                                                        String taskPrompt,
                                                        String alertContext) throws GraphRunnerException {
        OverAllState state = new OverAllState();
        String plannerPrompt = taskPrompt;
        String lastFeedback = "";
        int maxRounds = incidentProperties == null
                ? 8 : Math.max(1, incidentProperties.getMaxSupervisorRounds());

        for (int round = 1; round <= maxRounds; round++) {
            Optional<OverAllState> plannerState = invokeAgent(
                    plannerAgent, plannerPrompt, "aiOpsPlannerInvoke");
            if (plannerState.isEmpty()) {
                return Optional.empty();
            }
            String plannerOutput = stateText(plannerState.get(), "planner_plan");
            if (plannerOutput.isBlank()) {
                logger.warn("Planner 第 {} 轮没有返回控制输出，进入安全收口", round);
                break;
            }
            state.updateState(Map.of("planner_plan", new AssistantMessage(plannerOutput)));

            DiagnosisOrchestrationPolicy.Decision decision = orchestrationPolicy.classify(plannerOutput);
            logger.info("Planner 第 {} 轮代码侧决策: {}, reason={}", round,
                    decision.action(), decision.reason());
            if (decision.action() == DiagnosisOrchestrationPolicy.Action.FINISH_REPORT) {
                return Optional.of(state);
            }
            if (decision.action() == DiagnosisOrchestrationPolicy.Action.STOP_AND_FINALIZE) {
                break;
            }

            if (decision.action() == DiagnosisOrchestrationPolicy.Action.EXECUTE) {
                Optional<OverAllState> executorState = invokeAgent(
                        executorAgent,
                        buildExecutorRoundPrompt(taskPrompt, plannerOutput, lastFeedback),
                        "aiOpsExecutorInvoke");
                if (executorState.isEmpty()) {
                    break;
                }
                String executorOutput = stateText(executorState.get(), "executor_feedback");
                if (executorOutput.isBlank()) {
                    logger.warn("Executor 第 {} 轮没有返回反馈，进入安全收口", round);
                    break;
                }
                java.util.Set<String> usableEvidenceIds = diagnosisEvidenceRecorder != null
                        && diagnosisEvidenceRecorder.hasActiveRun()
                        ? diagnosisEvidenceRecorder.activeUsableEvidenceIds()
                        : null;
                if (!orchestrationPolicy.isValidExecutorFeedback(executorOutput, usableEvidenceIds)) {
                    logger.warn("Executor 第 {} 轮反馈不符合 JSON schema 或未绑定当前成功证据，进入安全收口", round);
                    break;
                }
                state.updateState(Map.of("executor_feedback", new AssistantMessage(executorOutput)));
                lastFeedback = executorOutput;
                if (orchestrationPolicy.requestsStop(executorOutput)) {
                    logger.warn("Executor 第 {} 轮返回停止信号，进入安全收口", round);
                    break;
                }
            }
            plannerPrompt = buildPlannerRoundPrompt(taskPrompt, plannerOutput, lastFeedback, round);
        }

        logger.warn("代码侧编排达到终止条件，进入单次最终报告收口");
        return finalizeReport(chatModel, state, alertContext);
    }

    private String buildPlannerRoundPrompt(String taskPrompt,
                                           String plannerOutput,
                                           String executorFeedback,
                                           int round) {
        return taskPrompt
                + "\n\n## 当前代码侧编排状态\n"
                + "当前轮次: " + round
                + "\n上一轮 Planner 输出:\n" + safePromptValue(plannerOutput)
                + "\n上一轮 Executor 反馈:\n" + safePromptValue(executorFeedback)
                + "\n请严格输出单个 JSON 对象（不得带前置说明或代码围栏），decision 只能是 PLAN、EXECUTE 或 FINISH；PLAN/EXECUTE 必须有不超过 500 字的 step，EXECUTE 必须有白名单 tool。";
    }

    private String buildExecutorRoundPrompt(String taskPrompt,
                                            String plannerOutput,
                                            String executorFeedback) {
        return taskPrompt
                + "\n\n## Planner 当前计划\n" + safePromptValue(plannerOutput)
                + "\n## 上一轮 Executor 反馈\n" + safePromptValue(executorFeedback)
                + "\n只执行 Planner 计划中的第一步，并输出单个 JSON 执行反馈对象。";
    }

    private Optional<OverAllState> finalizeReport(DashScopeChatModel chatModel,
                                                   OverAllState state,
                                                   String alertContext) throws GraphRunnerException {
        logger.warn("代码侧编排器未返回可入库的最终报告，启动最终报告收口 Agent");
        ReactAgent finalReportAgent = buildFinalReportAgent(chatModel);
        String finalizationPrompt = buildFinalizationPrompt(state, alertContext);
        Optional<OverAllState> finalState = invokeAgent(
                finalReportAgent, finalizationPrompt, "aiOpsFinalReportInvoke");
        AssistantMessage finalReport = finalState.flatMap(result -> result.value("final_report"))
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .orElse(null);
        String finalReportText = finalReport == null ? null : finalReport.getText();
        if (finalReportText == null || finalReportText.isBlank()
                || !DiagnosisReportService.isFinalReportCandidate(finalReportText)) {
            logger.warn("最终报告收口 Agent 返回为空或未通过报告结构校验");
            return Optional.of(state);
        }
        state.updateState(Map.of("final_report", finalReport));
        logger.info("最终报告收口 Agent 返回内容，长度: {}", finalReportText.length());
        return Optional.of(state);
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    private Optional<OverAllState> invokeAgent(Agent agent,
                                               String prompt,
                                               String operation)
            throws GraphRunnerException {
        long startedNanos = System.nanoTime();
        String previousOperation = MDC.get(AgentObservationHandler.AI_OPERATION_MDC_KEY);
        MDC.put(AgentObservationHandler.AI_OPERATION_MDC_KEY, operation);
        try {
            Optional<OverAllState> result;
            if (dependencyGuard == null) {
                result = agent.invoke(prompt);
            } else {
                result = dependencyGuard.execute("dashscope-chat", operation,
                        () -> {
                            try {
                                return agent.invoke(prompt);
                            } catch (GraphRunnerException e) {
                                throw new GraphRunnerCallException(e);
                            }
                        },
                        error -> {
                            if (error instanceof DependencyUnavailableException unavailable) {
                                throw unavailable;
                            }
                            throw new DependencyUnavailableException(
                                    "dashscope-chat", operation, "DEPENDENCY_ERROR", error);
                        });
            }
            recordModelInvocation(operation, "SUCCESS", startedNanos);
            return result;
        } catch (GraphRunnerCallException e) {
            recordModelInvocation(operation, "ERROR", startedNanos);
            GraphRunnerException cause = e.getGraphRunnerException();
            throw new GraphRunnerException(cause.getMessage(), cause);
        } catch (GraphRunnerException e) {
            recordModelInvocation(operation, "ERROR", startedNanos);
            throw e;
        } catch (RuntimeException e) {
            recordModelInvocation(operation, "ERROR", startedNanos);
            throw e;
        } finally {
            if (previousOperation == null) {
                MDC.remove(AgentObservationHandler.AI_OPERATION_MDC_KEY);
            } else {
                MDC.put(AgentObservationHandler.AI_OPERATION_MDC_KEY, previousOperation);
            }
        }
    }

    private void recordModelInvocation(String operation, String outcome, long startedNanos) {
        if (observabilityMetrics != null) {
            observabilityMetrics.recordModelInvocation(operation, null, outcome,
                    System.nanoTime() - startedNanos);
        }
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        if (state == null) {
            logger.warn("无法提取最终报告：Agent 状态为空");
            return Optional.empty();
        }

        // 收口 Agent 的结果优先；planner_plan 也会保存中间 PLAN/EXECUTE JSON，不能直接入库。
        Optional<AssistantMessage> finalReportOutput = state.value("final_report")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        Optional<AssistantMessage> candidate = finalReportOutput.or(() -> plannerFinalOutput);
        if (candidate.isPresent()) {
            String reportText = candidate.get().getText();
            if (reportText == null || reportText.isBlank()) {
                logger.warn("最终报告为空");
                return Optional.empty();
            }
            Matcher decisionMatcher = DECISION_PATTERN.matcher(reportText);
            boolean containsDecision = decisionMatcher.find();
            if (containsDecision || !DiagnosisReportService.isFinalReportCandidate(reportText)) {
                String decision = containsDecision ? decisionMatcher.group(1) : "未满足最终报告模板";
                logger.warn("Agent 返回的内容不是最终告警分析报告，拒绝入库, reason: {}", decision);
                return Optional.empty();
            }
            logger.info("成功提取到最终告警分析报告，长度: {}", reportText.length());
            return Optional.of(reportText);
        } else {
            logger.warn("未能提取到最终报告候选");
            return Optional.empty();
        }
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(DashScopeChatModel chatModel,
                                         ToolCallback[] toolCallbacks,
                                         boolean recordToolEvidence) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤")
                .model(chatModel)
                .chatOptions(structuredJsonChatOptions())
                .systemPrompt(buildPlannerPrompt())
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(agentRecursionLimit())
                        .build())
                .methodTools(buildPlannerMethodToolsArray())
                .tools(recordMcpEvidence(
                        agentToolSurfaceService.aiOpsPlannerMcpTools(toolCallbacks), recordToolEvidence))
                .outputKey("planner_plan")
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(DashScopeChatModel chatModel,
                                          ToolCallback[] toolCallbacks,
                                          boolean recordToolEvidence) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(chatModel)
                .chatOptions(structuredJsonChatOptions())
                .systemPrompt(buildExecutorPrompt())
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(agentRecursionLimit())
                        .build())
                .methodTools(buildExecutorMethodToolsArray())
                .hooks(buildExecutorToolCallHooks())
                .tools(recordMcpEvidence(
                        agentToolSurfaceService.aiOpsExecutorMcpTools(toolCallbacks), recordToolEvidence))
                .outputKey("executor_feedback")
                .build();
    }

    private ReactAgent buildFinalReportAgent(DashScopeChatModel chatModel) {
        return ReactAgent.builder()
                .name("final_report_agent")
                .description("根据已采集的告警上下文和证据生成最终告警分析报告")
                .model(chatModel)
                .systemPrompt(buildFinalReportPrompt())
                .outputKey("final_report")
                .build();
    }

    private DashScopeChatOptions structuredJsonChatOptions() {
        return DashScopeChatOptions.builder()
                .responseFormat(new DashScopeResponseFormat(DashScopeResponseFormat.Type.JSON_OBJECT))
                .build();
    }

    private String buildFinalizationPrompt(OverAllState state, String alertContext) {
        String latestEvidence = "";
        if (diagnosisEvidenceRecorder != null && diagnosisEvidenceRecorder.hasActiveRun()
                && diagnosisReportService != null) {
            latestEvidence = diagnosisReportService.buildEvidenceTable(
                    diagnosisEvidenceRecorder.activeEvidenceSnapshot());
        }
        return promptCatalog.finalizationPrompt().formatted(
                safePromptValue(alertContext),
                runbookPolicy.promptFor(alertContext),
                stateValueText(state, "planner_plan"),
                stateValueText(state, "executor_feedback"),
                safePromptValue(latestEvidence));
    }

    private String buildFinalReportPrompt() {
        return promptCatalog.finalReportPrompt();
    }

    private String stateValueText(OverAllState state, String key) {
        return state.value(key)
                .map(value -> value instanceof AssistantMessage message
                        ? message.getText()
                        : String.valueOf(value))
                .filter(value -> value != null && !value.isBlank())
                .orElse("无");
    }

    private String stateText(OverAllState state, String key) {
        return state.value(key)
                .map(value -> value instanceof AssistantMessage message
                        ? message.getText()
                        : String.valueOf(value))
                .filter(value -> value != null && !value.isBlank())
                .orElse("");
    }

    private String safePromptValue(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    private Object[] buildPlannerMethodToolsArray() {
        return agentToolSurfaceService.aiOpsPlannerMethodTools();
    }

    private Object[] buildExecutorMethodToolsArray() {
        return agentToolSurfaceService.aiOpsExecutorMethodTools();
    }

    private Hook[] buildExecutorToolCallHooks() {
        AppIncidentProperties properties = incidentProperties == null
                ? new AppIncidentProperties()
                : incidentProperties;
        List<Hook> hooks = new ArrayList<>();
        if (properties.getMaxToolAttemptsPerRun() >= 0) {
            hooks.add(ToolCallLimitHook.builder()
                    .threadLimit(properties.getMaxToolAttemptsPerRun())
                    .exitBehavior(ToolCallLimitHook.ExitBehavior.END)
                    .build());
        }
        if (properties.getQueryLogsMaxAttemptsPerRun() >= 0) {
            hooks.add(ToolCallLimitHook.builder()
                    .toolName("queryLogs")
                    .threadLimit(properties.getQueryLogsMaxAttemptsPerRun())
                    .exitBehavior(ToolCallLimitHook.ExitBehavior.END)
                    .build());
        }
        return hooks.toArray(new Hook[0]);
    }

    private int agentRecursionLimit() {
        int rounds = incidentProperties == null ? 8 : Math.max(1, incidentProperties.getMaxSupervisorRounds());
        return rounds * 4 + 4;
    }

    private ToolCallback[] recordMcpEvidence(ToolCallback[] callbacks, boolean recordToolEvidence) {
        if (!recordToolEvidence || diagnosisEvidenceRecorder == null) {
            return callbacks;
        }
        return diagnosisEvidenceRecorder.wrapToolCallbacks(callbacks);
    }

    private static class GraphRunnerCallException extends RuntimeException {
        private final GraphRunnerException graphRunnerException;

        private GraphRunnerCallException(GraphRunnerException graphRunnerException) {
            super(graphRunnerException);
            this.graphRunnerException = graphRunnerException;
        }

        private GraphRunnerException getGraphRunnerException() {
            return graphRunnerException;
        }
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return promptCatalog.plannerPrompt();
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return promptCatalog.executorPrompt();
    }

}
