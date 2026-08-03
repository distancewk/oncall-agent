package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiOpsPromptCatalogTest {

    private final AiOpsPromptCatalog catalog = new AiOpsPromptCatalog();

    @Test
    void resourcesShouldBePresentVersionedAndUseRuntimeMetricCatalog() {
        assertTrue(catalog.versionSummary().contains("planner=" + AiOpsPromptCatalog.PLANNER_VERSION));
        assertTrue(catalog.versionSummary().contains("executor=" + AiOpsPromptCatalog.EXECUTOR_VERSION));
        assertTrue(catalog.versionSummary().contains("finalReport=" + AiOpsPromptCatalog.FINAL_REPORT_VERSION));
        assertTrue(catalog.versionSummary().contains("finalization=" + AiOpsPromptCatalog.FINALIZATION_VERSION));
        assertFalse(catalog.plannerPrompt().contains("{{SUPPORTED_METRICS}}"));
        assertFalse(catalog.executorPrompt().contains("{{SUPPORTED_METRICS}}"));
        assertTrue(catalog.plannerPrompt().contains("queryMetricTrend"));
        assertTrue(catalog.executorPrompt().contains("TOOL_BUDGET_EXCEEDED"));
        assertTrue(catalog.finalReportPrompt().contains("# 告警分析报告"));
        assertTrue(catalog.finalizationPrompt().contains("## Planner 最近输出"));
    }

    @Test
    void startupValidationShouldCompleteForPackagedResources() {
        // validate() is intentionally package-visible so the startup contract remains directly testable.
        catalog.validate();
    }
}
