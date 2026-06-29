package org.example.controller;

import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.service.AlertService;
import org.example.service.IncidentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 接收监控告警主动推送的 Webhook 控制器
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private AlertService alertService;

    @Autowired
    private IncidentService incidentService;


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
        Optional<DiagnosisRunRecord> reusedRun = incidentService.createReusedDiagnosisRunIfAvailable(
                incident.getId(), alertContext);
        if (reusedRun.isPresent()) {
            DiagnosisRunRecord run = reusedRun.get();
            alertService.storeReport(alertId, run.getReport());
            logger.info("告警命中可复用诊断报告, alertId: {}, incidentId: {}, runId: {}, sourceRunId: {}",
                    alertId, incident.getId(), run.getRunId(), run.getReusedFromRunId());
            return ResponseEntity.ok("Alert received and reused existing diagnosis report.");
        }

        incidentService.createDiagnosisRunAndEnqueue(incident.getId(), alertContext, alertId);

        return ResponseEntity.ok("Alert received and processing triggered.");
    }

}
