package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

class DiagnosisOrchestrationPolicyTest {

    private final DiagnosisOrchestrationPolicy policy = new DiagnosisOrchestrationPolicy();

    @Test
    void classify_shouldRouteOnlyRecognizedPlannerDecisions() {
        assertEquals(DiagnosisOrchestrationPolicy.Action.PLAN,
                policy.classify("{\"decision\":\"PLAN\",\"step\":\"识别告警类型\"}").action());
        assertEquals(DiagnosisOrchestrationPolicy.Action.EXECUTE,
                policy.classify("{\"decision\":\"EXECUTE\",\"step\":\"查询日志\",\"tool\":\"queryLogs\"}").action());
        assertEquals(DiagnosisOrchestrationPolicy.Action.STOP_AND_FINALIZE,
                policy.classify("{\"decision\":\"FINISH\"}").action());
        assertEquals(DiagnosisOrchestrationPolicy.Action.STOP_AND_FINALIZE,
                policy.classify("not control json").action());
        assertEquals(DiagnosisOrchestrationPolicy.Action.STOP_AND_FINALIZE,
                policy.classify("前置说明 {\"decision\":\"EXECUTE\",\"step\":\"查询日志\",\"tool\":\"queryLogs\"}").action());
        assertEquals(DiagnosisOrchestrationPolicy.Action.STOP_AND_FINALIZE,
                policy.classify("```json\n{\"decision\":\"PLAN\",\"step\":\"识别告警\"}\n```").action());
        assertEquals(DiagnosisOrchestrationPolicy.Action.STOP_AND_FINALIZE,
                policy.classify("{\"decision\":\"EXECUTE\",\"step\":\"查询日志\",\"tool\":\"shell\"}").action());
    }

    @Test
    void classify_shouldAcceptAValidatedMarkdownReportAsTerminalOutput() {
        String report = """
                # 告警分析报告
                ## 告警根因分析
                证据不足
                ## 处理方案执行
                继续观察
                ## 结论
                无法完成
                ## 置信度
                低
                ## 缺失证据
                - 指标趋势
                """;

        assertEquals(DiagnosisOrchestrationPolicy.Action.FINISH_REPORT,
                policy.classify(report).action());
    }

    @Test
    void requestsStop_shouldRecognizeOnlyExplicitSafetySignals() {
        assertTrue(policy.requestsStop("{\"stop\": true, \"reason\": \"budget\"}"));
        assertTrue(policy.requestsStop("TOOL_BUDGET_EXCEEDED"));
        assertTrue(policy.requestsStop("UNSUPPORTED_METRIC"));
        assertFalse(policy.requestsStop("{\"status\":\"SUCCESS\"}"));
    }

    @Test
    void classify_shouldRejectMissingStepOrMalformedParameters() {
        assertEquals(DiagnosisOrchestrationPolicy.Action.STOP_AND_FINALIZE,
                policy.classify("{\"decision\":\"PLAN\"}").action());
        assertEquals(DiagnosisOrchestrationPolicy.Action.STOP_AND_FINALIZE,
                policy.classify("{\"decision\":\"EXECUTE\",\"step\":\"查询\",\"tool\":\"queryLogs\",\"parameters\":[]}").action());
    }

    @Test
    void isValidExecutorFeedback_shouldRequireStructuredEvidenceClaims() {
        assertTrue(policy.isValidExecutorFeedback("""
                {"status":"SUCCESS","summary":"发现错误日志","evidence":[{"id":"ev-1","claim":"出现超时"}],"nextHint":"检查下游"}
                """));
        assertFalse(policy.isValidExecutorFeedback("工具调用完成，但没有结构化证据"));
        assertFalse(policy.isValidExecutorFeedback("""
                {"status":"SUCCESS","summary":"发现错误日志","evidence":[{"id":"ev-1"}]}
                """));
        assertFalse(policy.isValidExecutorFeedback("""
                {"status":"SUCCESS","summary":"发现错误日志","evidence":[]} trailing
                """));
        assertFalse(policy.isValidExecutorFeedback("""
                {"status":"SUCCESS","summary":"发现错误日志","evidence":[{"id":"fake-1","claim":"出现超时"}]}
                """));
    }

    @Test
    void isValidExecutorFeedback_shouldBindEvidenceToSuccessfulRunIds() {
        String valid = """
                {"status":"SUCCESS","summary":"发现超时","evidence":[{"id":"ev-real-1","claim":"出现超时"}]}
                """;
        assertTrue(policy.isValidExecutorFeedback(valid, Set.of("ev-real-1")));
        assertFalse(policy.isValidExecutorFeedback(valid, Set.of("ev-other-1")));
        assertFalse(policy.isValidExecutorFeedback(
                "{\"status\":\"SUCCESS\",\"summary\":\"发现超时\",\"evidence\":[{\"id\":\"ev-real-1\",\"claim\":\"出现超时\"},{\"id\":\"ev-real-1\",\"claim\":\"重复\"}]}",
                Set.of("ev-real-1")));
    }

    @Test
    void controlSchemas_shouldRejectUnknownFieldsAndOversizedText() {
        assertEquals(DiagnosisOrchestrationPolicy.Action.STOP_AND_FINALIZE,
                policy.classify("{\"decision\":\"PLAN\",\"step\":\"检查\",\"extra\":true}").action());
        assertFalse(policy.isValidExecutorFeedback(
                "{\"status\":\"SUCCESS\",\"summary\":\"发现\",\"evidence\":[],\"extra\":true}"));
    }
}
