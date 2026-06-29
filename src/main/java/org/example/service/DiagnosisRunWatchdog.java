package org.example.service;

import org.example.dto.DiagnosisRunRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DiagnosisRunWatchdog {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosisRunWatchdog.class);

    private final IncidentService incidentService;

    public DiagnosisRunWatchdog(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @Scheduled(fixedDelayString = "${app.incidents.stale-run-sweep-delay-millis:60000}")
    public void failStaleRuns() {
        List<DiagnosisRunRecord> failedRuns = incidentService.markStaleRunsFailed();
        if (!failedRuns.isEmpty()) {
            LOGGER.warn("自动标记 {} 个超时诊断任务为失败", failedRuns.size());
        }
    }
}
