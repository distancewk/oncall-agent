package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.BackgroundJobRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisJobHandlerTest {

    @Test
    void cancelledJob_shouldSkipAiOpsExecution() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        AiOpsService aiOpsService = mock(AiOpsService.class);
        BackgroundJobRepository jobRepository = mock(BackgroundJobRepository.class);
        BackgroundJobRecord job = job(
                "{\"incidentId\":\"inc-1\",\"runId\":\"run-1\",\"alertContext\":\"ctx\"}");
        when(jobRepository.isCancelRequested("job-1")).thenReturn(true);

        DiagnosisJobHandler handler = new DiagnosisJobHandler(
                new ObjectMapper(), incidentService, aiOpsService,
                mock(DashScopeChatModel.class), mock(ToolCallbackProvider.class),
                null, null, jobRepository, null);

        handler.handle(job);

        verify(aiOpsService, never()).executeAiOpsAnalysis(any(), any(), any(), any(), any());
        verify(incidentService, never()).markRunRunning(any(), any());
    }

    private BackgroundJobRecord job(String payload) {
        BackgroundJobRecord job = new BackgroundJobRecord();
        job.setJobId("job-1");
        job.setJobType("DIAGNOSIS");
        job.setBusinessKey("run-1");
        job.setPayload(payload);
        job.setAttemptCount(1);
        job.setMaxAttempts(2);
        return job;
    }
}
