package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.dto.AlertPayload;
import org.example.service.AiOpsService;
import org.example.service.AlertService;
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

        // 存储告警到 AlertService
        String alertId = alertService.storeAlert(payload);
        logger.info("告警已存储, alertId: {}", alertId);

        // 构建告警上下文文本
        String alertContext = buildAlertContext(payload);

        // 异步触发 AiOps 分析流程
        executor.execute(() -> {
            try {
                logger.info("开始主动执行告警分析任务, alertId: {}...", alertId);
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
                    } else {
                        alertService.storeReport(alertId, "未能生成告警分析报告");
                        logger.warn("主动触发告警分析完成，但未能提取到最终报告");
                    }
                } else {
                    alertService.storeReport(alertId, "告警分析执行失败，未获取到有效结果");
                    logger.warn("主动触发告警分析失败，未能获取到有效结果状态");
                }
            } catch (Exception e) {
                logger.error("主动告警分析执行异常", e);
                alertService.storeReport(alertId, "告警分析异常: " + e.getMessage());
            }
        });

        return ResponseEntity.ok("Alert received and processing triggered.");
    }

    /**
     * 构建告警上下文文本，用于传递给 AI Ops 分析
     */
    private String buildAlertContext(AlertPayload payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("告警状态: ").append(payload.getStatus()).append("\n");
        sb.append("接收者: ").append(payload.getReceiver()).append("\n");

        if (payload.getCommonLabels() != null && !payload.getCommonLabels().isEmpty()) {
            sb.append("公共标签:\n");
            payload.getCommonLabels().forEach((k, v) -> sb.append("  - ").append(k).append(": ").append(v).append("\n"));
        }

        if (payload.getCommonAnnotations() != null && !payload.getCommonAnnotations().isEmpty()) {
            sb.append("公共注解:\n");
            payload.getCommonAnnotations().forEach((k, v) -> sb.append("  - ").append(k).append(": ").append(v).append("\n"));
        }

        sb.append("告警列表:\n");
        if (payload.getAlerts() != null) {
            for (int i = 0; i < payload.getAlerts().size(); i++) {
                AlertPayload.Alert alert = payload.getAlerts().get(i);
                sb.append("  [").append(i + 1).append("]\n");
                sb.append("    状态: ").append(alert.getStatus()).append("\n");
                sb.append("    开始时间: ").append(alert.getStartsAt()).append("\n");
                if (alert.getLabels() != null) {
                    sb.append("    标签:\n");
                    alert.getLabels().forEach((k, v) -> sb.append("      - ").append(k).append(": ").append(v).append("\n"));
                }
                if (alert.getAnnotations() != null) {
                    sb.append("    注解:\n");
                    alert.getAnnotations().forEach((k, v) -> sb.append("      - ").append(k).append(": ").append(v).append("\n"));
                }
            }
        }

        return sb.toString();
    }
}
