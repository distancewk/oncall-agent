package org.example.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppJobProperties;
import org.example.dto.ApiResponse;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.dto.IncidentSummary;
import org.example.service.IncidentCaseService;
import org.example.service.IncidentService;
import org.example.service.BackgroundJobRepository;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private static final Logger logger = LoggerFactory.getLogger(IncidentController.class);

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentCaseService incidentCaseService;

    @Autowired
    private BackgroundJobRepository backgroundJobRepository;

    @Autowired
    private AppJobProperties jobProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<ApiResponse<List<IncidentSummary>>> listIncidents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String latestRunStatus,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String humanReviewStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int pageSize) {
        return ResponseEntity.ok(ApiResponse.success(incidentService.listIncidents(
                status, severity, latestRunStatus, q, humanReviewStatus, page, pageSize)));
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

    @GetMapping("/{incidentId}/runs/{runId}/evidence")
    public ResponseEntity<ApiResponse<List<org.example.dto.DiagnosisEvidence>>> getRunEvidence(
            @PathVariable String incidentId,
            @PathVariable String runId) {
        return incidentService.getRunEvidence(incidentId, runId)
                .map(evidence -> ResponseEntity.ok(ApiResponse.success(evidence)))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error(404, "DiagnosisRun 不存在")));
    }

    @PostMapping("/{incidentId}/runs/{runId}/cancel")
    public ResponseEntity<ApiResponse<DiagnosisRunRecord>> cancelRun(@PathVariable String incidentId,
                                                                     @PathVariable String runId,
                                                                     @RequestParam(required = false) String reason) {
        try {
            DiagnosisRunRecord run = incidentService.cancelRun(incidentId, runId, reason);
            return ResponseEntity.ok(ApiResponse.success(run));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "DiagnosisRun 不存在"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "当前诊断状态不允许取消"));
        }
    }

    @PostMapping("/{incidentId}/runs/{runId}/confirm")
    public ResponseEntity<ApiResponse<DiagnosisRunRecord>> confirmRun(@PathVariable String incidentId,
                                                                      @PathVariable String runId,
                                                                      @RequestParam(required = false) String comment) {
        try {
            incidentService.confirmRun(incidentId, runId, comment);
            try {
                enqueueCaseArchive(incidentId, runId);
                DiagnosisRunRecord queued = incidentService.markRunCaseArchived(
                        incidentId, runId, false, null, "已确认，历史案例入库已排队");
                return ResponseEntity.ok(ApiResponse.success(queued));
            } catch (Exception archiveError) {
                logger.warn("人工确认后创建历史案例后台任务失败, incidentId: {}, runId: {}",
                        incidentId, runId, archiveError);
                DiagnosisRunRecord updated = incidentService.markRunCaseArchived(
                        incidentId,
                        runId,
                        false,
                        null,
                        "历史案例写入失败，请稍后重试");
                return ResponseEntity.ok(ApiResponse.success(updated));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "DiagnosisRun 不存在"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "当前诊断状态不允许确认"));
        }
    }

    @PostMapping("/{incidentId}/runs/{runId}/reject")
    public ResponseEntity<ApiResponse<DiagnosisRunRecord>> rejectRun(@PathVariable String incidentId,
                                                                     @PathVariable String runId,
                                                                     @RequestParam(required = false) String comment) {
        try {
            DiagnosisRunRecord run = incidentService.rejectRun(incidentId, runId, comment);
            return ResponseEntity.ok(ApiResponse.success(run));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "DiagnosisRun 不存在"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "当前诊断状态不允许驳回"));
        }
    }

    @PostMapping("/{incidentId}/diagnose")
    public ResponseEntity<ApiResponse<DiagnosisRunRecord>> diagnoseIncident(
            @PathVariable String incidentId,
            @RequestParam(defaultValue = "false") boolean force) {
        Optional<IncidentRecord> incidentOptional = incidentService.getIncident(incidentId);
        if (incidentOptional.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Incident 不存在"));
        }

        String alertContext = incidentService.buildAlertContext(incidentOptional.get());
        Optional<DiagnosisRunRecord> reusedRun = force
                ? Optional.empty()
                : incidentService.createReusedDiagnosisRunIfAvailable(incidentId, alertContext);
        if (reusedRun.isPresent()) {
            logger.info("复用历史诊断报告, incidentId: {}, runId: {}, sourceRunId: {}",
                    incidentId, reusedRun.get().getRunId(), reusedRun.get().getReusedFromRunId());
            return ResponseEntity.ok(ApiResponse.success(reusedRun.get()));
        }

        if (force) {
            logger.info("强制重新执行诊断，跳过历史报告复用, incidentId: {}", incidentId);
        }

        DiagnosisRunRecord run = incidentService.createDiagnosisRunAndEnqueue(incidentId, alertContext);
        return ResponseEntity.ok(ApiResponse.success(run));
    }

    @PostMapping("/{incidentId}/archive-case")
    public ResponseEntity<ApiResponse<IncidentCaseService.ArchiveResult>> archiveCase(@PathVariable String incidentId) {
        IncidentCaseService.ArchiveResult result = incidentCaseService.archiveCase(incidentId);
        if (result.isSuccess()) {
            incidentService.getIncident(incidentId)
                    .flatMap(incident -> incident.getDiagnosisRuns().stream()
                            .filter(run -> "CONFIRMED".equals(run.getHumanReviewStatus())
                                    && ("COMPLETED".equals(run.getStatus())
                                    || "COMPLETED_WITH_GAPS".equals(run.getStatus())))
                            .reduce((first, second) -> second))
                    .ifPresent(run -> incidentService.markRunCaseArchived(
                            incidentId, run.getRunId(), true, result.getDocumentId(), result.getMessage()));
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private void enqueueCaseArchive(String incidentId, String runId) {
        try {
            String payload = objectMapper.writeValueAsString(java.util.Map.of(
                    "incidentId", incidentId, "runId", runId));
            backgroundJobRepository.enqueue(
                    "ARCHIVE_CASE", runId, payload, jobProperties.getArchiveMaxAttempts(),
                    System.currentTimeMillis());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("创建历史案例后台任务失败: " + runId, e);
        }
    }

    @GetMapping("/{incidentId}/similar-cases")
    public ResponseEntity<ApiResponse<List<VectorSearchService.SearchResult>>> similarCases(@PathVariable String incidentId,
                                                                                            @RequestParam(required = false) Integer topK) {
        return ResponseEntity.ok(ApiResponse.success(incidentCaseService.findSimilarCases(incidentId, topK == null ? 3 : topK)));
    }

}
