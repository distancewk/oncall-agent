package org.example.controller;

import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.service.AlertService;
import org.example.service.IncidentService;
import org.example.service.IncidentStore;
import org.example.service.AlertIdentity;
import org.example.service.WebhookSignatureVerifier;
import org.example.service.TenantContext;
import org.example.service.TenantIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
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

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebhookSignatureVerifier webhookSignatureVerifier;


    @PostMapping("/alert")
    public ResponseEntity<String> receiveAlert(HttpServletRequest request, @RequestBody String rawBody) {
        if (!webhookSignatureVerifier.verifyAndClaim(request, rawBody)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        AlertPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, AlertPayload.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid alert payload");
        }
        String tenantId;
        try {
            tenantId = TenantIdentity.fromAlert(payload);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid tenant identity");
        }
        request.setAttribute(TenantContext.TENANT_ID_ATTRIBUTE, tenantId);
        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            return processAlert(payload);
        }
    }

    private ResponseEntity<String> processAlert(AlertPayload payload) {
        logger.info("收到 Alertmanager Webhook 推送, status: {}, alert count: {}", payload.getStatus(), payload.getAlerts() != null ? payload.getAlerts().size() : 0);

        if (payload.getAlerts() == null || payload.getAlerts().isEmpty()) {
            return ResponseEntity.ok("No alerts to process.");
        }

        String alertId = AlertIdentity.id(payload, objectMapper);
        if (alertService.getAlert(alertId) != null) {
            logger.info("忽略重复告警投递, alertId: {}", alertId);
            return ResponseEntity.ok("Alert already processed.");
        }
        IncidentStore.RecordAlertResult recordResult = incidentService.recordAlertWithStatus(payload, alertId);
        IncidentRecord incident = recordResult.incident();
        if (!recordResult.created()) {
            logger.info("忽略并发重复告警投递, alertId: {}, incidentId: {}", alertId, incident.getId());
            return response("Alert already processed.", incident.getId(), null);
        }
        logger.info("告警已存储, alertId: {}, incidentId: {}", alertId, incident.getId());

        String alertContext = incidentService.buildAlertContext(incident);
        Optional<DiagnosisRunRecord> reusedRun = incidentService.createReusedDiagnosisRunIfAvailable(
                incident.getId(), alertContext);
        if (reusedRun.isPresent()) {
            DiagnosisRunRecord run = reusedRun.get();
            alertService.storeReport(alertId, run.getReport());
            logger.info("告警命中可复用诊断报告, alertId: {}, incidentId: {}, runId: {}, sourceRunId: {}",
                    alertId, incident.getId(), run.getRunId(), run.getReusedFromRunId());
            return response("Alert received and reused existing diagnosis report.",
                    incident.getId(), run.getRunId());
        }

        DiagnosisRunRecord queuedRun = incidentService.createDiagnosisRunAndEnqueue(
                incident.getId(), alertContext, alertId);

        return response("Alert received and processing triggered.", incident.getId(), queuedRun.getRunId());
    }

    /**
     * Additive response headers let trusted automation correlate an accepted
     * webhook with the durable Incident and DiagnosisRun without parsing logs.
     * The existing plain-text response body remains backward compatible.
     */
    private ResponseEntity<String> response(String body, String incidentId, String runId) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (incidentId != null && !incidentId.isBlank()) {
            builder.header("X-Incident-ID", incidentId);
        }
        if (runId != null && !runId.isBlank()) {
            builder.header("X-Diagnosis-Run-ID", runId);
        }
        return builder.body(body);
    }

}
