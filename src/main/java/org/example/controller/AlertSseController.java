package org.example.controller;

import org.example.dto.AlertPayload;
import org.example.dto.IncidentRecord;
import org.example.service.AlertService;
import org.example.service.IncidentService;
import org.example.service.AlertService.StoredAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

/**
 * 告警 SSE 推送与查询控制器
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertSseController {

    private static final Logger logger = LoggerFactory.getLogger(AlertSseController.class);

    @Autowired
    private AlertService alertService;

    @Autowired(required = false)
    private IncidentService incidentService;

    @Value("${app.alerts.simulate-enabled:false}")
    private boolean simulateEnabled;

    /**
     * SSE 长连接，实时推送告警通知
     */
    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamAlerts() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // 无超时

        // 注册监听器
        AlertService.AlertListener listener = alert -> {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("type", "new_alert");
                data.put("alertId", alert.getId());
                data.put("incidentId", alert.getIncidentId());
                data.put("status", alert.getStatus());
                data.put("receivedAt", alert.getReceivedAt());

                // 提取告警摘要
                StringBuilder summary = new StringBuilder();
                if (alert.getAlerts() != null) {
                    for (AlertPayload.Alert a : alert.getAlerts()) {
                        String name = a.getLabels() != null ? a.getLabels().getOrDefault("alertname", "未知告警") : "未知告警";
                        String severity = a.getLabels() != null ? a.getLabels().getOrDefault("severity", "unknown") : "unknown";
                        summary.append("[").append(severity).append("] ").append(name).append("; ");
                    }
                }
                data.put("summary", summary.toString());

                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                logger.warn("向 SSE 客户端推送告警通知失败", e);
                // 连接已断开，抛出异常让框架处理
                throw new RuntimeException(e);
            }
        };

        alertService.addListener(listener);

        // 连接完成/超时/出错时移除监听器
        emitter.onCompletion(() -> alertService.removeListener(listener));
        emitter.onTimeout(() -> alertService.removeListener(listener));
        emitter.onError(e -> alertService.removeListener(listener));

        logger.info("新的 SSE 告警流客户端已连接");
        return emitter;
    }

    /**
     * 获取告警历史列表
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getAlertHistory() {
        List<StoredAlert> alerts = alertService.getAlerts();
        List<Map<String, Object>> alertList = new ArrayList<>();

        for (StoredAlert alert : alerts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", alert.getId());
            item.put("incidentId", alert.getIncidentId());
            item.put("status", alert.getStatus());
            item.put("receivedAt", alert.getReceivedAt());

            // 提取告警摘要
            StringBuilder summary = new StringBuilder();
            String severity = "unknown";
            if (alert.getAlerts() != null) {
                for (AlertPayload.Alert a : alert.getAlerts()) {
                    String name = a.getLabels() != null ? a.getLabels().getOrDefault("alertname", "未知告警") : "未知告警";
                    String sev = a.getLabels() != null ? a.getLabels().getOrDefault("severity", "unknown") : "unknown";
                    if (!"unknown".equals(sev)) severity = sev;
                    summary.append("[").append(sev).append("] ").append(name).append("; ");
                }
            }
            item.put("summary", summary.toString());
            item.put("severity", severity);
            item.put("hasReport", alertService.getReport(alert.getId()) != null);
            alertList.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alerts", alertList);
        result.put("total", alertList.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定告警的详情
     */
    @GetMapping("/detail/{alertId}")
    public ResponseEntity<Map<String, Object>> getAlertDetail(@PathVariable String alertId) {
        StoredAlert alert = alertService.getAlert(alertId);
        if (alert == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", alert.getId());
        result.put("status", alert.getStatus());
        result.put("receivedAt", alert.getReceivedAt());
        result.put("alerts", alert.getAlerts());
        result.put("groupLabels", alert.getGroupLabels());
        result.put("commonLabels", alert.getCommonLabels());
        result.put("commonAnnotations", alert.getCommonAnnotations());

        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定告警的分析报告
     */
    @GetMapping("/report/{alertId}")
    public ResponseEntity<Map<String, Object>> getAlertReport(@PathVariable String alertId) {
        String report = alertService.getReport(alertId);
        if (report == null) {
            // 检查告警是否存在
            StoredAlert alert = alertService.getAlert(alertId);
            if (alert == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("alertId", alertId, "report", "", "status", "pending"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alertId", alertId);
        result.put("report", report);
        result.put("status", "completed");
        return ResponseEntity.ok(result);
    }

    /**
     * 模拟告警（用于前端测试）
     */
    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulateAlert(@RequestBody(required = false) Map<String, Object> body) {
        if (!simulateEnabled) {
            logger.warn("模拟告警接口未开启，拒绝请求");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", "模拟告警接口未开启"));
        }

        logger.info("收到模拟告警请求");

        // 构造模拟告警
        AlertPayload payload = new AlertPayload();
        payload.setReceiver("webhook");
        payload.setStatus("firing");
        payload.setGroupLabels(Map.of("alertname", "SimulatedAlert"));
        payload.setCommonLabels(Map.of("severity", "critical", "env", "production"));
        payload.setCommonAnnotations(Map.of("summary", "这是一个模拟告警", "description", "用于前端测试的模拟告警"));

        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setStartsAt(new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new java.util.Date()));

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("alertname", "SimulatedAlert");
        labels.put("severity", "critical");
        labels.put("instance", "test-server-01");
        labels.put("job", "test-service");
        alert.setLabels(labels);

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("summary", "模拟告警：CPU 使用率超过 90%");
        annotations.put("description", "模拟告警：instance=test-server-01 的 CPU 使用率当前值为 95%，超过阈值 90%");
        alert.setAnnotations(annotations);

        payload.setAlerts(List.of(alert));

        IncidentRecord incident = null;
        if (incidentService != null) {
            incident = incidentService.recordAlert(payload);
            logger.info("模拟告警已聚合到 Incident, incidentId: {}", incident.getId());
        }
        String alertId = incident != null
                ? alertService.storeAlert(payload, incident.getId())
                : alertService.storeAlert(payload);
        logger.info("模拟告警已存储, alertId: {}", alertId);

        // 异步触发分析
        // 注意：这里不自动触发 AiOps 分析，避免重复。前端可通过主动查询触发。

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("alertId", alertId);
        if (incident != null) {
            result.put("incidentId", incident.getId());
        }
        result.put("message", "模拟告警已触发");
        return ResponseEntity.ok(result);
    }
}
