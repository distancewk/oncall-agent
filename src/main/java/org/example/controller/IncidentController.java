package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.dto.IncidentSummary;
import org.example.service.IncidentCaseService;
import org.example.service.IncidentService;
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
                IncidentCaseService.ArchiveResult archiveResult = incidentCaseService.archiveCase(incidentId, runId);
                DiagnosisRunRecord archived = incidentService.markRunCaseArchived(
                        incidentId,
                        runId,
                        archiveResult.isSuccess(),
                        archiveResult.getDocumentId(),
                        archiveResult.getMessage());
                return ResponseEntity.ok(ApiResponse.success(archived));
            } catch (Exception archiveError) {
                logger.warn("人工确认后写入历史案例失败, incidentId: {}, runId: {}",
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
    public ResponseEntity<ApiResponse<DiagnosisRunRecord>> diagnoseIncident(@PathVariable String incidentId) {
        Optional<IncidentRecord> incidentOptional = incidentService.getIncident(incidentId);
        if (incidentOptional.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Incident 不存在"));
        }

        String alertContext = incidentService.buildAlertContext(incidentOptional.get());
        Optional<DiagnosisRunRecord> reusedRun = incidentService.createReusedDiagnosisRunIfAvailable(incidentId, alertContext);
        if (reusedRun.isPresent()) {
            logger.info("复用历史诊断报告, incidentId: {}, runId: {}, sourceRunId: {}",
                    incidentId, reusedRun.get().getRunId(), reusedRun.get().getReusedFromRunId());
            return ResponseEntity.ok(ApiResponse.success(reusedRun.get()));
        }

        DiagnosisRunRecord run = incidentService.createDiagnosisRunAndEnqueue(incidentId, alertContext);
        return ResponseEntity.ok(ApiResponse.success(run));
    }

    @PostMapping("/{incidentId}/archive-case")
    public ResponseEntity<ApiResponse<IncidentCaseService.ArchiveResult>> archiveCase(@PathVariable String incidentId) {
        return ResponseEntity.ok(ApiResponse.success(incidentCaseService.archiveCase(incidentId)));
    }

    @GetMapping("/{incidentId}/similar-cases")
    public ResponseEntity<ApiResponse<List<VectorSearchService.SearchResult>>> similarCases(@PathVariable String incidentId,
                                                                                            @RequestParam(required = false) Integer topK) {
        return ResponseEntity.ok(ApiResponse.success(incidentCaseService.findSimilarCases(incidentId, topK == null ? 3 : topK)));
    }

}
