package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.service.AiOpsService;
import org.example.service.AlertService;
import org.example.service.IncidentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * 接收监控告警主动推送的 Webhook 控制器
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private AiOpsService aiOpsService;

    @Autowired
    private AlertService alertService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    @Qualifier("dashScopeChatModelAiOps")
    private DashScopeChatModel dashScopeChatModel;

    @Autowired(required = false)
    private ToolCallbackProvider tools;

    @Autowired
    @Qualifier("chatTaskExecutor")
    private Executor executor;

    @PostMapping("/alert")
    public ResponseEntity<String> receiveAlert(@RequestBody AlertPayload payload) {
        logger.info("收到 Alertmanager Webhook 推送, status: {}, alert count: {}", payload.getStatus(), payload.getAlerts() != null ? payload.getAlerts().size() : 0);

        if (payload.getAlerts() == null || payload.getAlerts().isEmpty()) {
            return ResponseEntity.ok("No alerts to process.");
        }

        IncidentRecord incident = incidentService.recordAlert(payload);
        String alertId = alertService.storeAlert(payload, incident.getId());
        logger.info("告警已存储, alertId: {}, incidentId: {}", alertId, incident.getId());

        String alertContext = incidentService.buildAlertContext(incident);
        DiagnosisRunRecord run = incidentService.createDiagnosisRun(incident.getId(), alertContext);

        // 异步触发 AiOps 分析流程
        executor.execute(() -> {
            try {
                logger.info("开始主动执行告警分析任务, alertId: {}, incidentId: {}, runId: {}...",
                        alertId, incident.getId(), run.getRunId());
                incidentService.markRunRunning(incident.getId(), run.getRunId());
                DashScopeChatModel chatModel = dashScopeChatModel;

                ToolCallback[] toolCallbacks = tools != null ? tools.getToolCallbacks() : new ToolCallback[0];
                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks, alertContext);

                if (overAllStateOptional.isPresent()) {
                    Optional<String> finalReport = aiOpsService.extractFinalReport(overAllStateOptional.get());
                    if (finalReport.isPresent()) {
                        String report = finalReport.get();
                        logger.info("主动触发告警分析完成，生成的报告如下：\n{}", report);
                        // 存储分析报告到 AlertService
                        alertService.storeReport(alertId, report);
                        incidentService.completeRun(incident.getId(), run.getRunId(), report);
                    } else {
                        String errorMessage = "未能生成告警分析报告";
                        alertService.storeReport(alertId, errorMessage);
                        incidentService.failRun(incident.getId(), run.getRunId(), errorMessage);
                        logger.warn("主动触发告警分析完成，但未能提取到最终报告");
                    }
                } else {
                    String errorMessage = "告警分析执行失败，未获取到有效结果";
                    alertService.storeReport(alertId, errorMessage);
                    incidentService.failRun(incident.getId(), run.getRunId(), errorMessage);
                    logger.warn("主动触发告警分析失败，未能获取到有效结果状态");
                }
            } catch (Exception e) {
                logger.error("主动告警分析执行异常", e);
                String errorMessage = "告警分析异常: " + e.getMessage();
                alertService.storeReport(alertId, errorMessage);
                incidentService.failRun(incident.getId(), run.getRunId(), errorMessage);
            }
        });

        return ResponseEntity.ok("Alert received and processing triggered.");
    }
}
