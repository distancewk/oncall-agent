package org.example.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AiOpsServiceTest {

    private AiOpsService aiOpsService;

    @Mock
    private org.example.agent.tool.DateTimeTools dateTimeTools;

    @Mock
    private org.example.agent.tool.InternalDocsTools internalDocsTools;

    @Mock
    private org.example.agent.tool.QueryMetricsTools queryMetricsTools;

    @BeforeEach
    void setUp() {
        aiOpsService = new AiOpsService();
        ReflectionTestUtils.setField(aiOpsService, "dateTimeTools", dateTimeTools);
        ReflectionTestUtils.setField(aiOpsService, "internalDocsTools", internalDocsTools);
        ReflectionTestUtils.setField(aiOpsService, "queryMetricsTools", queryMetricsTools);
    }

    @Test
    void extractFinalReport_shouldReturnEmpty_whenStateDoesNotContainPlannerPlan() {
        OverAllState state = new OverAllState();
        Optional<String> report = aiOpsService.extractFinalReport(state);
        assertTrue(report.isEmpty());
    }

    @Test
    void extractFinalReport_shouldReturnReport_whenStateContainsPlannerPlan() {
        String expectedReport = "# 告警分析报告\n\n测试报告内容";
        AssistantMessage message = new AssistantMessage(expectedReport);
        OverAllState state = new OverAllState(Map.of("planner_plan", message));

        Optional<String> report = aiOpsService.extractFinalReport(state);
        assertTrue(report.isPresent());
        assertEquals(expectedReport, report.get());
    }

    @Test
    void extractFinalReport_shouldReturnEmpty_whenPlannerPlanIsNotAssistantMessage() {
        OverAllState state = new OverAllState(Map.of("planner_plan", "just a string"));

        Optional<String> report = aiOpsService.extractFinalReport(state);
        assertTrue(report.isEmpty());
    }
}
