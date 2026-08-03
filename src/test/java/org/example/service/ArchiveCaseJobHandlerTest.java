package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.BackgroundJobRecord;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveCaseJobHandlerTest {

    @Test
    void handle_shouldArchiveConfirmedRunAndUpdateRunState() throws Exception {
        IncidentCaseService incidentCaseService = mock(IncidentCaseService.class);
        IncidentService incidentService = mock(IncidentService.class);
        BackgroundJobRepository jobRepository = mock(BackgroundJobRepository.class);
        BackgroundJobRecord job = new BackgroundJobRecord();
        job.setJobId("job-archive-1");
        job.setTenantId("tenant-a");
        job.setPayload("{\"incidentId\":\"incident-1\",\"runId\":\"run-1\","
                + "\"tenantId\":\"tenant-a\"}");
        job.setAttemptCount(1);
        job.setMaxAttempts(3);

        IncidentCaseService.ArchiveResult result = new IncidentCaseService.ArchiveResult();
        result.setSuccess(true);
        result.setDocumentId("case-doc-1");
        result.setMessage("历史案例已写入知识库");
        AtomicReference<String> observedTenant = new AtomicReference<>();
        when(incidentCaseService.archiveCase("incident-1", "run-1")).thenAnswer(invocation -> {
            observedTenant.set(TenantContext.currentTenant());
            return result;
        });

        ArchiveCaseJobHandler handler = new ArchiveCaseJobHandler(
                new ObjectMapper(), incidentCaseService, incidentService, jobRepository);

        handler.handle(job);

        verify(incidentCaseService).archiveCase("incident-1", "run-1");
        verify(incidentService).markRunCaseArchived(
                "incident-1", "run-1", true, "case-doc-1", "历史案例已写入知识库");
        assertEquals("tenant-a", observedTenant.get());
        assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantContext.currentTenant());
    }

    @Test
    void handle_shouldRejectPayloadTenantThatDiffersFromPersistedJobTenant() {
        IncidentCaseService incidentCaseService = mock(IncidentCaseService.class);
        IncidentService incidentService = mock(IncidentService.class);
        BackgroundJobRepository jobRepository = mock(BackgroundJobRepository.class);
        BackgroundJobRecord job = new BackgroundJobRecord();
        job.setTenantId("tenant-a");
        job.setPayload("{\"incidentId\":\"incident-1\",\"runId\":\"run-1\","
                + "\"tenantId\":\"tenant-b\"}");

        ArchiveCaseJobHandler handler = new ArchiveCaseJobHandler(
                new ObjectMapper(), incidentCaseService, incidentService, jobRepository);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(job));
    }
}
