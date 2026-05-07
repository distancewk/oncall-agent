package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.dto.AlertPayload;
import org.example.service.AiOpsService;
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
    @Qualifier("dashScopeChatModelAiOps")
    private DashScopeChatModel dashScopeChatModel;

    @Autowired
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

        // 异步触发 AiOps 分析流程
        executor.execute(() -> {
            try {
                logger.info("开始主动执行告警分析任务...");
                DashScopeChatModel chatModel = dashScopeChatModel;

                ToolCallback[] toolCallbacks = tools.getToolCallbacks();
                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);

                if (overAllStateOptional.isPresent()) {
                    Optional<String> finalReport = aiOpsService.extractFinalReport(overAllStateOptional.get());
                    if (finalReport.isPresent()) {
                        logger.info("主动触发告警分析完成，生成的报告如下：\n{}", finalReport.get());
                        // TODO: 未来可以将报告推送到企业微信、钉钉或存入数据库
                    } else {
                        logger.warn("主动触发告警分析完成，但未能提取到最终报告");
                    }
                } else {
                    logger.warn("主动触发告警分析失败，未能获取到有效结果状态");
                }
            } catch (Exception e) {
                logger.error("主动告警分析执行异常", e);
            }
        });

        return ResponseEntity.ok("Alert received and processing triggered.");
    }
}
