package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.dto.DiagnosisRunRecord;
import org.example.util.ToolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的告警分析流程
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    @Autowired(required = false)
    private DiagnosisEvidenceRecorder diagnosisEvidenceRecorder;

    @Autowired(required = false)
    private DiagnosisReportService diagnosisReportService;

    @Autowired(required = false)
    private IncidentService incidentService;

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
        return executeAiOpsAnalysisInternal(chatModel, toolCallbacks, alertContext);
    }

    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel,
                                                       ToolCallback[] toolCallbacks,
                                                       String alertContext,
                                                       String incidentId,
                                                       String runId) throws GraphRunnerException {
        String evidenceBoundContext = buildEvidenceBoundAlertContext(alertContext, incidentId, runId);
        if (diagnosisEvidenceRecorder == null || incidentId == null || incidentId.isBlank()
                || runId == null || runId.isBlank()) {
            return executeAiOpsAnalysisInternal(chatModel, toolCallbacks, evidenceBoundContext);
        }
        try {
            ToolCallback[] recordingCallbacks = diagnosisEvidenceRecorder.wrapToolCallbacks(toolCallbacks);
            return diagnosisEvidenceRecorder.withRun(incidentId, runId,
                    () -> executeAiOpsAnalysisInternal(chatModel, recordingCallbacks, evidenceBoundContext));
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

    private Optional<OverAllState> executeAiOpsAnalysisInternal(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks, String alertContext) throws GraphRunnerException {
        logger.info("开始执行 AI Ops 多 Agent 协作流程");

        // 构建 Planner 和 Executor Agent
        ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks);
        ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks);

        // 构建 Supervisor Agent
        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
                .model(chatModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

        String taskPrompt = "你是企业级 SRE，接到了自动化告警排查任务。请结合工具调用，执行**规划→执行→再规划**的闭环，并最终按照固定模板输出《告警分析报告》。禁止编造虚假数据，如连续多次查询失败需诚实反馈无法完成的原因。报告结论必须绑定 evidence id；证据不足时必须显式说明缺失证据。";

        if (alertContext != null && !alertContext.isEmpty()) {
            taskPrompt += "\n\n## 当前告警上下文\n" + alertContext + "\n\n请基于以上告警上下文进行分析和处理。";
            logger.info("已注入告警上下文，长度: {}", alertContext.length());
        }

        logger.info("调用 Supervisor Agent 开始编排...");
        return supervisorAgent.invoke(taskPrompt);
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        // 提取 Planner 最终输出（包含完整的告警分析报告）
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        if (plannerFinalOutput.isPresent()) {
            String reportText = plannerFinalOutput.get().getText();
            if (reportText == null || reportText.isBlank()) {
                logger.warn("Planner 最终报告为空");
                return Optional.empty();
            }
            logger.info("成功提取到 Planner 最终报告，长度: {}", reportText.length());
            return Optional.of(reportText);
        } else {
            logger.warn("未能提取到 Planner 最终报告");
            return Optional.empty();
        }
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("planner_plan")
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("executor_feedback")
                .build();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    private Object[] buildMethodToolsArray() {
        return ToolUtils.buildMethodToolsArray(dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools);
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析 Prometheus 告警、日志、内部文档等信息，制定可执行的下一步步骤。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期要调用的工具、以及必要的上下文。
                4. 调用任何腾讯云日志/主题相关工具时，region 参数必须使用连字符格式（如 ap-guangzhou），若不确定请省略以使用默认值。
                5. 严格禁止编造数据，只能引用工具返回的真实内容；如果连续 3 次调用同一工具仍失败或返回空结果，需停止该方向并在最终报告的结论部分说明"无法完成"的原因。
                6. 遇到 CPU、内存、错误率、P99 延迟、重启次数相关告警时，必须规划调用 queryMetricTrend 查询 15m/1h/6h 中最相关窗口的趋势，再基于趋势证据判断根因。

                ## 工具调用策略（减少冗余）

                - 当前 Incident 告警上下文是首要事实源；如果输入中已经包含告警名称、级别、实例、服务、摘要、标签或注解，优先基于这些字段规划诊断。
                - queryPrometheusAlerts 是条件工具，用于确认全局告警面、发现关联告警或校验当前告警是否仍在 firing；不要把 queryPrometheusAlerts 作为默认第一步。
                - 推荐证据顺序：当前 Incident 告警上下文 -> 相似历史故障/已提供证据 -> queryMetricTrend -> queryLogs -> queryInternalDocs -> 条件性 queryPrometheusAlerts。
                - 只有无法根据告警类型推断日志主题时，才调用 getAvailableLogTopics；能推断时直接规划 queryLogs。
                - 日志主题推断规则：CPU/内存/磁盘使用率 -> system-metrics；错误率/服务不可用/慢响应/下游依赖 -> application-logs；慢 SQL/数据库性能 -> database-slow-query；OOMKilled/CrashLoop/重启/容器崩溃 -> system-events。
                - Tavily MCP 仅用于查询外部公开资料、官方文档、错误码说明和组件版本差异；不能用外部搜索结果覆盖 Incident、指标、日志或内部知识库中的事实。
                - 数据库 MCP 仅用于只读验证业务状态、配置、事件记录和 CMDB 信息；必须先说明要验证的问题，再规划有限范围查询。
                - 不要为了“补全流程”调用无关工具；每个工具调用都必须能回答当前诊断问题，并在报告中形成可引用 evidence。
                
                ## 最终报告输出要求（CRITICAL）
                
                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出完整的 Markdown 格式报告文本**
                3. **报告必须严格遵循以下模板**：
                
                ```
                # 告警分析报告
                
                ---
                
                ## 📋 活跃告警清单
                
                | 告警名称 | 级别 | 目标服务 | 首次触发时间 | 最新触发时间 | 状态 |
                |---------|------|----------|-------------|-------------|------|
                | [告警1名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                | [告警2名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                
                ---
                
                ## 🔍 告警根因分析1 - [告警名称]
                
                ### 告警详情
                - **告警级别**: [级别]
                - **受影响服务**: [服务名]
                - **持续时间**: [X分钟]
                
                ### 症状描述
                [根据监控指标描述症状]
                
                ### 日志证据
                [引用查询到的关键日志]
                
                ### 根因结论
                [基于证据得出的根本原因]
                
                ---
                
                ## 🛠️ 处理方案执行1 - [告警名称]
                
                ### 已执行的排查步骤
                1. [步骤1]
                2. [步骤2]
                
                ### 处理建议
                [给出具体的处理建议]
                
                ### 预期效果
                [说明预期的效果]
                
                ---
                
                ## 🔍 告警根因分析2 - [告警名称]
                [如果有第2个告警，重复上述格式]
                
                ---
                
                ## 📊 结论
                
                ### 整体评估
                [总结所有告警的整体情况]
                
                ### 关键发现
                - [发现1]
                - [发现2]

                ### 置信度
                [高/中/低；说明置信度来自哪些 evidence id，以及哪些结论仍缺少证据]

                ### 缺失证据
                - [缺失证据1；如果没有，写“无”]
                - [缺失证据2；如果没有，写“无”]
                
                ### 后续建议
                1. [建议1]
                2. [建议2]
                
                ### 风险评估
                [评估当前风险等级和影响范围]
                ```
                
                **重要提醒**：
                - 最终输出必须是纯 Markdown 文本，不要包含 JSON 结构
                - 不要使用 "finalReport": "..." 这样的格式
                - 直接从 "# 告警分析报告" 开始输出
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 工具返回中若包含 _diagnosisEvidenceId，报告中引用对应结论时必须标注 [evidence: ev-xxxx]
                - 每个根因、症状和处理建议必须绑定 evidence id；不能被 evidence id 支撑的判断必须写“证据不足”
                - 报告必须明确写出“置信度”和“缺失证据”
                - 资源、错误率、延迟、重启类结论必须引用 queryMetricTrend 的趋势 evidence id；如果趋势查询失败，必须明确说明趋势证据缺失
                - 根因、症状和处理建议应尽量引用 evidence id；无法拿到证据 id 时，必须说明证据来源缺失
                - 如果某个步骤失败，在结论中如实说明，不要跳过
                
                """;
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数，尤其是 region 参数要使用连字符格式（ap-guangzhou）；若 Planner 未给出则使用默认区域。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                - 执行 CPU、内存、错误率、P99 延迟或重启次数排查时，必须优先调用 queryMetricTrend，传入 metric、service、instance、window、step，获取趋势摘要后再继续日志或文档查询。
                - 已有明确告警上下文时，不要重复查询活动告警；只有 Planner 明确要求确认全局告警面、关联告警或 firing 状态时，才调用 queryPrometheusAlerts。
                - 能从告警类型推断日志主题时，直接调用 queryLogs；只有 Planner 未给出主题且无法从告警类型推断时，才调用 getAvailableLogTopics。
                - 日志主题推断规则：CPU/内存/磁盘使用率 -> system-metrics；错误率/服务不可用/慢响应/下游依赖 -> application-logs；慢 SQL/数据库性能 -> database-slow-query；OOMKilled/CrashLoop/重启/容器崩溃 -> system-events。
                - 调用 Tavily MCP 时，只能查询公开资料、官方文档、错误码或版本差异，并在反馈中注明其属于外部参考。
                - 调用数据库 MCP 时只能执行只读查询；禁止执行 INSERT / UPDATE / DELETE / DROP / ALTER / TRUNCATE / CREATE 等写入或结构变更语句，查询必须限制字段、时间范围和返回行数。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的告警名称或资源，方便 Planner 填充"告警根因分析 / 处理方案执行"章节。
                - 工具返回中若包含 _diagnosisEvidenceId，必须把该 id 原样写入 evidence 列表，格式为 [evidence: ev-xxxx]。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。


                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "近1小时未见 error 日志，仅有 info",
                  "evidence": "...",
                  "nextHint": "建议转向高占用进程"
                }
                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词
     */
    private String buildSupervisorSystemPrompt() {
        return """
                你是 AI Ops Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                4. FINISH 后，确保向最终用户输出完整的《告警分析报告》，格式必须严格为：
                   告警分析报告\n---\n# 告警处理详情\n## 活跃告警清单\n## 告警根因分析N\n## 处理方案执行N\n## 结论。
                5. 若步骤涉及腾讯云日志/主题工具，请确保使用连字符区域 ID（ap-guangzhou 等），或省略 region 以采用默认值。
                6. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，必须终止流程，直接输出"任务无法完成"的报告，明确告知失败原因，严禁凭空编造结果。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。

                """;
    }
}
