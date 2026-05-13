package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.dto.ApiResponse;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.dto.IncidentSummary;
import org.example.service.AiOpsService;
import org.example.service.IncidentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private static final Logger logger = LoggerFactory.getLogger(IncidentController.class);

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private AiOpsService aiOpsService;

    @Autowired
    @Qualifier("dashScopeChatModelAiOps")
    private DashScopeChatModel dashScopeChatModelAiOps;

    @Autowired(required = false)
    private ToolCallbackProvider tools;

    @Autowired
    @Qualifier("chatTaskExecutor")
    private Executor executor;

    @GetMapping
    public ResponseEntity<ApiResponse<List<IncidentSummary>>> listIncidents() {
        return ResponseEntity.ok(ApiResponse.success(incidentService.listIncidents()));
    }

    @GetMapping("/{incidentId}")
    public ResponseEntity<ApiResponse<IncidentRecord>> getIncident(@PathVariable String incidentId) {
        return incidentService.getIncident(incidentId)
                .map(record -> ResponseEntity.ok(ApiResponse.success(record)))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error(404, "Incident 不存在")));
    }

    @GetMapping("/{incidentId}/runs")
    public ResponseEntity<ApiResponse<List<DiagnosisRunRecord>>> getDiagnosisRuns(@PathVariable String incidentId) {
        return incidentService.getDiagnosisRuns(incidentId)
                .map(runs -> ResponseEntity.ok(ApiResponse.success(runs)))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error(404, "Incident 不存在")));
    }

    @PostMapping("/{incidentId}/diagnose")
    public ResponseEntity<ApiResponse<DiagnosisRunRecord>> diagnoseIncident(@PathVariable String incidentId) {
        Optional<IncidentRecord> incidentOptional = incidentService.getIncident(incidentId);
        if (incidentOptional.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Incident 不存在"));
        }

        String alertContext = incidentService.buildAlertContext(incidentOptional.get());
        DiagnosisRunRecord run = incidentService.createDiagnosisRun(incidentId, alertContext);
        executor.execute(() -> executeDiagnosisRun(incidentId, run.getRunId(), alertContext));
        return ResponseEntity.ok(ApiResponse.success(run));
    }

    private void executeDiagnosisRun(String incidentId, String runId, String alertContext) {
        try {
            logger.info("开始执行 Incident 诊断, incidentId: {}, runId: {}", incidentId, runId);
            incidentService.markRunRunning(incidentId, runId);
            ToolCallback[] toolCallbacks = tools != null ? tools.getToolCallbacks() : new ToolCallback[0];
            Optional<OverAllState> stateOptional = aiOpsService.executeAiOpsAnalysis(
                    dashScopeChatModelAiOps, toolCallbacks, alertContext);
            if (stateOptional.isEmpty()) {
                incidentService.failRun(incidentId, runId, "告警分析执行失败，未获取到有效结果");
                return;
            }

            Optional<String> reportOptional = aiOpsService.extractFinalReport(stateOptional.get());
            if (reportOptional.isPresent()) {
                incidentService.completeRun(incidentId, runId, reportOptional.get());
            } else {
                incidentService.failRun(incidentId, runId, "多 Agent 流程完成，但未能提取最终报告");
            }
        } catch (Exception e) {
            logger.error("Incident 诊断执行异常, incidentId: {}, runId: {}", incidentId, runId, e);
            incidentService.failRun(incidentId, runId, "告警分析异常: " + e.getMessage());
        }
    }
}
