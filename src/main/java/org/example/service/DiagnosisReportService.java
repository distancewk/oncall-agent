package org.example.service;

import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DiagnosisReportService {

    private static final Pattern EVIDENCE_REF_PATTERN = Pattern.compile("\\[evidence:\\s*([^\\]\\s]+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALIDATION_SECTION_PATTERN = Pattern.compile("(?ms)^## 证据校验\\s*$.*?(?=^## (?!证据校验)|\\z)");
    private static final Pattern CONFIDENCE_SECTION_PATTERN = Pattern.compile("(?ms)^(### 置信度\\s*$).*?(?=^### |^## |\\z)");
    private static final int SUMMARY_LIMIT = 180;
    private static final String TOOL_DUPLICATE_SKIPPED = "TOOL_DUPLICATE_SKIPPED";
    private static final String TOOL_BUDGET_EXCEEDED = "TOOL_BUDGET_EXCEEDED";

    public static boolean isFinalReportCandidate(String report) {
        if (report == null || report.isBlank()) {
            return false;
        }
        String safeReport = report.trim();
        if (!safeReport.startsWith("# 告警分析报告")) {
            return false;
        }
        if (safeReport.matches("(?is).*['\"]decision['\"]\\s*:\\s*['\"](?:PLAN|EXECUTE|FINISH)['\"].*")) {
            return false;
        }
        return safeReport.contains("告警根因分析")
                && safeReport.contains("处理方案执行")
                && safeReport.contains("结论")
                && safeReport.contains("置信度")
                && safeReport.contains("缺失证据");
    }

    public String augmentAlertContext(String alertContext, List<DiagnosisEvidence> evidence) {
        StringBuilder builder = new StringBuilder(alertContext == null ? "" : alertContext.trim());
        String evidenceTable = buildEvidenceTable(evidence);
        if (!evidenceTable.isBlank()) {
            builder.append("\n\n").append(evidenceTable);
        }
        builder.append("\n\n").append(reportRules());
        return builder.toString();
    }

    public String buildEvidenceTable(DiagnosisRunRecord run) {
        return buildEvidenceTable(run == null ? List.of() : run.getEvidence());
    }

    public String buildEvidenceTable(List<DiagnosisEvidence> evidence) {
        List<DiagnosisEvidence> usableEvidence = usableToolEvidence(evidence);
        List<DiagnosisEvidence> failedEvidence = failedToolEvidence(evidence);
        List<DiagnosisEvidence> skippedEvidence = skippedToolEvidence(evidence);
        if (usableEvidence.isEmpty() && failedEvidence.isEmpty() && skippedEvidence.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        if (!usableEvidence.isEmpty()) {
            builder.append("## 可用工具证据表（仅成功）\n");
            builder.append("| Evidence ID | 类型 | 工具 | 时间范围 | 状态 | 尝试次数 | 耗时(ms) | 可重试 | 摘要 |\n");
            builder.append("|---|---|---|---|---|---|---|---|---|\n");
            for (DiagnosisEvidence item : usableEvidence) {
                builder.append("| ")
                        .append(cell(item.getId()))
                        .append(" | ")
                        .append(cell(item.getType()))
                        .append(" | ")
                        .append(cell(item.getToolName()))
                        .append(" | ")
                        .append(cell(item.getTimeRange()))
                        .append(" | success | ")
                        .append(item.getAttemptCount())
                        .append(" | ")
                        .append(item.getDurationMs())
                        .append(" | ")
                        .append(item.isRetryable())
                        .append(" | ")
                        .append(cell(compact(item.getSummary(), SUMMARY_LIMIT)))
                        .append(" |\n");
            }
        }
        if (!failedEvidence.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append("## 失败工具证据说明\n");
            builder.append("| Evidence ID | 工具 | 状态 | 错误码 | 尝试次数 | 耗时(ms) | 可重试 | 摘要 |\n");
            builder.append("|---|---|---|---|---|---|---|---|\n");
            for (DiagnosisEvidence item : failedEvidence) {
                builder.append("| ")
                        .append(cell(item.getId()))
                        .append(" | ")
                        .append(cell(item.getToolName()))
                        .append(" | failed | ")
                        .append(cell(item.getErrorCode()))
                        .append(" | ")
                        .append(item.getAttemptCount())
                        .append(" | ")
                        .append(item.getDurationMs())
                        .append(" | ")
                        .append(item.isRetryable())
                        .append(" | ")
                        .append(cell(compact(item.getSummary(), SUMMARY_LIMIT)))
                        .append(" |\n");
            }
        }
        if (!skippedEvidence.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append("## 工具策略拦截说明（不可作为失败或事实证据）\n");
            builder.append("| Evidence ID | 工具 | 状态 | 错误码 | 摘要 |\n");
            builder.append("|---|---|---|---|---|\n");
            for (DiagnosisEvidence item : skippedEvidence) {
                builder.append("| ")
                        .append(cell(item.getId()))
                        .append(" | ")
                        .append(cell(item.getToolName()))
                        .append(" | skipped | ")
                        .append(cell(item.getErrorCode()))
                        .append(" | ")
                        .append(cell(compact(item.getSummary(), SUMMARY_LIMIT)))
                        .append(" |\n");
            }
        }
        return builder.toString().trim();
    }

    public String guardReport(String report, DiagnosisRunRecord run) {
        return constrainReport(report, run == null ? List.of() : run.getEvidence());
    }

    public QualityAssessment evaluateQuality(String report, List<DiagnosisEvidence> evidence) {
        String safeReport = stripExistingValidationSection(report == null ? "" : report).trim();
        EvidenceReferenceContext refs = evidenceReferenceContext(safeReport, evidence);

        List<String> issues = new ArrayList<>();
        int score = 100;
        if (safeReport.isBlank()) {
            issues.add("报告为空");
            score -= 50;
        }
        if (refs.toolEvidence().isEmpty()) {
            issues.add("没有工具 evidence");
            score -= 35;
        }
        if (!refs.toolEvidence().isEmpty() && refs.citedIds().isEmpty()) {
            issues.add("报告没有引用工具 evidence id");
            score = Math.min(score, 59);
        }
        if (refs.usableEvidence().isEmpty()) {
            issues.add("没有成功工具 evidence");
            score = Math.min(score, 59);
        }
        if (hasCitedMockEvidence(refs.knownCitedIds(), refs.usableEvidenceById())) {
            issues.add("报告引用了 Mock 数据，不能视为真实诊断证据");
            score = Math.min(score, 59);
        }
        if (!refs.unknownIds().isEmpty()) {
            issues.add("引用了未知 evidence");
            score -= 30;
        }
        if (!refs.invalidIds().isEmpty()) {
            issues.add("引用了失败或不可用 evidence");
            score -= 30;
        }
        if (containsResourceClaim(safeReport)
                && !hasCitedSuccessfulTool(refs.knownCitedIds(), refs.usableEvidenceById(), "queryMetricTrend")) {
            issues.add("资源类结论缺少成功的趋势 evidence");
            score -= 25;
        }
        boolean hasSuccessfulLogs = hasCitedSuccessfulTool(refs.knownCitedIds(), refs.usableEvidenceById(), "queryLogs");
        boolean containsJvmClaim = containsJvmEvidenceClaim(safeReport);
        boolean hasSuccessfulJvmText = hasCitedEvidenceText(refs.knownCitedIds(), refs.usableEvidenceById(),
                "GC", "Full GC", "gc_", "OutOfMemory", "OutOfMemoryError", "OOM");
        boolean hasSuccessfulJvmMetric = hasCitedSuccessfulMetric(refs.knownCitedIds(), refs.usableEvidenceById(),
                "jvm_gc_collection_seconds_count");
        if (containsJvmClaim && !hasSuccessfulLogs && !hasSuccessfulJvmText && !hasSuccessfulJvmMetric) {
            issues.add("GC/Full GC 结论缺少对应日志或 JVM 指标 evidence");
            score -= 15;
        }
        if (containsLogClaim(safeReport) && !hasSuccessfulLogs
                && !(containsJvmClaim && (hasSuccessfulJvmText || hasSuccessfulJvmMetric))) {
            issues.add("日志/异常结论缺少成功日志 evidence");
            score -= 15;
        }
        long failedToolEvidence = failedToolEvidence(evidence).size();
        if (!refs.toolEvidence().isEmpty() && failedToolEvidence > 0) {
            int failedRatioPenalty = (int) Math.min(20,
                    Math.round((failedToolEvidence * 20.0d) / refs.toolEvidence().size()));
            score -= failedRatioPenalty;
        }

        score = Math.max(0, Math.min(100, score));
        String grade = score >= 80 ? "HIGH" : score >= 60 ? "MEDIUM" : "LOW";
        String summary = grade + " score=" + score
                + ", usableEvidence=" + refs.usableEvidence().size()
                + ", failedEvidence=" + failedToolEvidence
                + ", citedEvidence=" + refs.knownCitedIds().size();
        return new QualityAssessment(score, grade, summary, List.copyOf(issues));
    }

    public String constrainReport(String report, List<DiagnosisEvidence> evidence) {
        String safeReport = stripExistingValidationSection(report == null ? "" : report).trim();
        EvidenceReferenceContext refs = evidenceReferenceContext(safeReport, evidence);

        List<String> missingEvidence = findMissingEvidence(safeReport, refs.usableEvidenceById(),
                refs.knownCitedIds(), refs.toolEvidence());
        boolean citedMockEvidence = hasCitedMockEvidence(refs.knownCitedIds(), refs.usableEvidenceById());
        String confidence = confidence(refs.unknownIds(), refs.invalidIds(), missingEvidence,
                refs.knownCitedIds(), citedMockEvidence);
        String status = validationStatus(refs.unknownIds(), refs.invalidIds(), missingEvidence, citedMockEvidence);

        String normalizedReport = normalizeConfidenceSection(safeReport, confidence);
        return normalizedReport
                + "\n\n---\n\n"
                + "## 证据校验\n"
                + "- 校验状态: " + status + "\n"
                + "- 置信度: " + confidence + "\n"
                + "- 已引用证据: " + (refs.knownCitedIds().isEmpty() ? "无" : String.join(", ", refs.knownCitedIds())) + "\n"
                + "- 未知证据: " + (refs.unknownIds().isEmpty() ? "无" : String.join(", ", refs.unknownIds())) + "\n"
                + "- 无效/失败证据: " + invalidEvidenceSummary(refs.invalidIds(), refs.allEvidenceById()) + "\n"
                + "- 缺失证据: " + (missingEvidence.isEmpty() ? "无" : String.join("; ", missingEvidence)) + "\n"
                + "- 可用证据: " + availableEvidenceSummary(refs.usableEvidence()) + "\n"
                + "- 约束说明: 以上校验仅基于已持久化的工具 evidence；未被 evidence 支撑的结论应按证据不足处理。\n";
    }

    private String normalizeConfidenceSection(String report, String confidence) {
        Matcher matcher = CONFIDENCE_SECTION_PATTERN.matcher(report);
        if (!matcher.find()) {
            return report;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + "\n" + confidence + "\n"));
    }

    private EvidenceReferenceContext evidenceReferenceContext(String report, List<DiagnosisEvidence> evidence) {
        List<DiagnosisEvidence> toolEvidence = toolEvidence(evidence);
        List<DiagnosisEvidence> usableEvidence = usableToolEvidence(evidence);
        Map<String, DiagnosisEvidence> allEvidenceById = evidenceById(toolEvidence);
        Map<String, DiagnosisEvidence> usableEvidenceById = evidenceById(usableEvidence);
        Set<String> citedIds = extractEvidenceIds(report);
        List<String> unknownIds = citedIds.stream()
                .filter(id -> !allEvidenceById.containsKey(id))
                .toList();
        List<String> invalidIds = citedIds.stream()
                .filter(id -> allEvidenceById.containsKey(id) && !usableEvidenceById.containsKey(id))
                .toList();
        Set<String> knownCitedIds = new LinkedHashSet<>(citedIds);
        knownCitedIds.removeAll(unknownIds);
        knownCitedIds.removeAll(invalidIds);
        return new EvidenceReferenceContext(toolEvidence, usableEvidence, allEvidenceById, usableEvidenceById,
                citedIds, unknownIds, invalidIds, knownCitedIds);
    }

    private List<String> findMissingEvidence(String report,
                                             Map<String, DiagnosisEvidence> evidenceById,
                                             Set<String> knownCitedIds,
                                             List<DiagnosisEvidence> toolEvidence) {
        List<String> missing = new ArrayList<>();
        if (!toolEvidence.isEmpty() && knownCitedIds.isEmpty()) {
            missing.add("报告没有引用任何工具 evidence id");
        }
        if (evidenceById.isEmpty()) {
            missing.add("没有成功工具 evidence");
        }
        if (hasCitedMockEvidence(knownCitedIds, evidenceById)) {
            missing.add("报告引用了 Mock 数据，缺少真实运行数据");
        }
        if (containsResourceClaim(report) && !hasCitedSuccessfulTool(knownCitedIds, evidenceById, "queryMetricTrend")) {
            missing.add("资源类结论缺少成功的 queryMetricTrend 趋势 evidence");
        }
        boolean hasSuccessfulLogs = hasCitedSuccessfulTool(knownCitedIds, evidenceById, "queryLogs");
        boolean containsJvmClaim = containsJvmEvidenceClaim(report);
        boolean hasSuccessfulJvmText = hasCitedEvidenceText(knownCitedIds, evidenceById,
                "GC", "Full GC", "gc_", "OutOfMemory", "OutOfMemoryError", "OOM");
        boolean hasSuccessfulJvmMetric = hasCitedSuccessfulMetric(knownCitedIds, evidenceById,
                "jvm_gc_collection_seconds_count");
        if (containsJvmClaim && !hasSuccessfulLogs && !hasSuccessfulJvmText && !hasSuccessfulJvmMetric) {
            missing.add("GC/Full GC 结论缺少对应日志或 JVM 指标 evidence");
        }
        if (containsLogClaim(report) && !hasSuccessfulLogs
                && !(containsJvmClaim && (hasSuccessfulJvmText || hasSuccessfulJvmMetric))) {
            missing.add("日志/异常结论缺少成功的 queryLogs evidence");
        }
        return missing;
    }

    private String stripExistingValidationSection(String report) {
        return VALIDATION_SECTION_PATTERN.matcher(value(report)).replaceAll("").trim();
    }

    private Set<String> extractEvidenceIds(String report) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = EVIDENCE_REF_PATTERN.matcher(report == null ? "" : report);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (notBlank(id)) {
                ids.add(id.trim());
            }
        }
        return ids;
    }

    private boolean hasCitedSuccessfulTool(Set<String> citedIds,
                                           Map<String, DiagnosisEvidence> evidenceById,
                                           String toolName) {
        return citedIds.stream()
                .map(evidenceById::get)
                .anyMatch(evidence -> evidence != null
                        && evidence.isSuccess()
                && toolName.equals(evidence.getToolName()));
    }

    private boolean hasCitedSuccessfulMetric(Set<String> citedIds,
                                             Map<String, DiagnosisEvidence> evidenceById,
                                             String metricName) {
        return citedIds.stream()
                .map(evidenceById::get)
                .anyMatch(evidence -> evidence != null
                        && evidence.isSuccess()
                        && "queryMetricTrend".equals(evidence.getToolName())
                && value(evidence.getQueryParams()).contains(metricName));
    }

    private boolean hasCitedMockEvidence(Set<String> citedIds,
                                         Map<String, DiagnosisEvidence> evidenceById) {
        return citedIds.stream()
                .map(evidenceById::get)
                .anyMatch(this::isMockEvidence);
    }

    private boolean isMockEvidence(DiagnosisEvidence evidence) {
        if (evidence == null) {
            return false;
        }
        String raw = value(evidence.getRawFragment())
                .replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
        return raw.contains("\"DATAMODE\":\"MOCK\"");
    }

    private boolean hasCitedEvidenceText(Set<String> citedIds,
                                         Map<String, DiagnosisEvidence> evidenceById,
                                         String... needles) {
        return citedIds.stream()
                .map(evidenceById::get)
                .filter(evidence -> evidence != null && evidence.isSuccess())
                .map(evidence -> String.join(" ",
                        value(evidence.getSummary()),
                        value(evidence.getContent()),
                        value(evidence.getRawFragment())))
                .anyMatch(text -> containsAny(text, needles));
    }

    private boolean containsResourceClaim(String report) {
        return containsAny(report, "CPU", "cpu", "内存", "memory", "错误率", "error_rate", "P99", "延迟", "latency", "restart", "重启");
    }

    private boolean containsLogClaim(String report) {
        return containsAny(report, "日志", "log", "ERROR", "OOM", "OutOfMemory", "异常日志", "错误日志");
    }

    private boolean containsGcClaim(String report) {
        return containsAny(report, "Full GC", "GC 风暴", "GC overhead", "Garbage Collection", "gc_");
    }

    private boolean containsJvmEvidenceClaim(String report) {
        return containsAny(report, "OOM", "OutOfMemory", "Full GC", "GC 风暴", "GC overhead", "Garbage Collection", "gc_");
    }

    private boolean containsAny(String text, String... needles) {
        String safeText = text == null ? "" : text;
        for (String needle : needles) {
            if (safeText.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String confidence(List<String> unknownIds,
                              List<String> invalidIds,
                              List<String> missingEvidence,
                              Set<String> knownCitedIds,
                              boolean citedMockEvidence) {
        if (!unknownIds.isEmpty() || !invalidIds.isEmpty()) {
            return "低";
        }
        if (citedMockEvidence) {
            return "低";
        }
        if (missingEvidence.isEmpty() && !knownCitedIds.isEmpty()) {
            return "高";
        }
        return "中";
    }

    private String validationStatus(List<String> unknownIds,
                                    List<String> invalidIds,
                                    List<String> missingEvidence,
                                    boolean citedMockEvidence) {
        if (!unknownIds.isEmpty() || !invalidIds.isEmpty()) {
            return "失败";
        }
        if (!missingEvidence.isEmpty() || citedMockEvidence) {
            return "需补证";
        }
        return "通过";
    }

    private String invalidEvidenceSummary(List<String> invalidIds, Map<String, DiagnosisEvidence> evidenceById) {
        if (invalidIds.isEmpty()) {
            return "无";
        }
        return invalidIds.stream()
                .map(id -> {
                    DiagnosisEvidence evidence = evidenceById.get(id);
                    String reason = evidence == null ? "unknown" : value(evidence.getErrorCode());
                    if (reason.isBlank()) {
                        reason = evidence != null && !evidence.isSuccess() ? "success=false" : "unusable";
                    }
                    return id + "(" + reason + ")";
                })
                .toList()
                .toString()
                .replace("[", "")
                .replace("]", "");
    }

    private Map<String, DiagnosisEvidence> evidenceById(List<DiagnosisEvidence> evidence) {
        Map<String, DiagnosisEvidence> evidenceById = new LinkedHashMap<>();
        for (DiagnosisEvidence item : evidence) {
            if (item != null && notBlank(item.getId())) {
                evidenceById.put(item.getId(), item);
            }
        }
        return evidenceById;
    }

    private String availableEvidenceSummary(List<DiagnosisEvidence> toolEvidence) {
        if (toolEvidence.isEmpty()) {
            return "无";
        }
        return toolEvidence.stream()
                .map(evidence -> value(evidence.getId())
                        + "("
                        + value(evidence.getToolName())
                        + ","
                        + evidenceStatus(evidence)
                        + (notBlank(evidence.getErrorCode()) ? "," + evidence.getErrorCode() : "")
                        + ")")
                .toList()
                .toString()
                .replace("[", "")
                .replace("]", "");
    }

    private List<DiagnosisEvidence> toolEvidence(List<DiagnosisEvidence> evidence) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .filter(item -> item != null && ("tool_call".equals(item.getType()) || notBlank(item.getToolName())))
                .toList();
    }

    private List<DiagnosisEvidence> usableToolEvidence(List<DiagnosisEvidence> evidence) {
        return toolEvidence(evidence).stream()
                .filter(item -> item.isSuccess() && !"CIRCUIT_OPEN".equals(item.getErrorCode()))
                .toList();
    }

    private List<DiagnosisEvidence> failedToolEvidence(List<DiagnosisEvidence> evidence) {
        return toolEvidence(evidence).stream()
                .filter(item -> !isPolicySkippedEvidence(item)
                        && (!item.isSuccess() || "CIRCUIT_OPEN".equals(item.getErrorCode())))
                .toList();
    }

    private List<DiagnosisEvidence> skippedToolEvidence(List<DiagnosisEvidence> evidence) {
        return toolEvidence(evidence).stream()
                .filter(this::isPolicySkippedEvidence)
                .toList();
    }

    private boolean isPolicySkippedEvidence(DiagnosisEvidence evidence) {
        return evidence != null
                && !evidence.isSuccess()
                && evidence.getAttemptCount() == 0
                && (TOOL_DUPLICATE_SKIPPED.equals(evidence.getErrorCode())
                || TOOL_BUDGET_EXCEEDED.equals(evidence.getErrorCode()));
    }

    private String evidenceStatus(DiagnosisEvidence evidence) {
        if (isPolicySkippedEvidence(evidence)) {
            return "skipped";
        }
        return evidence.isSuccess() ? "success" : "failed";
    }

    private String reportRules() {
        return """
                ## 报告证据约束
                - 最终报告中的每个根因、症状和处理建议必须引用上方成功工具 Evidence ID，格式为 [evidence: ev-xxxx]。
                - 资源类结论（CPU、内存、错误率、P99、重启）必须引用 queryMetricTrend 证据。
                - 日志、异常、OOM 结论必须引用 queryLogs；GC/Full GC 结论可引用 queryLogs 或 jvm_gc_collection_seconds_count 趋势 evidence。
                - 禁止使用未出现在成功证据表中的 evidence id；失败或熔断 evidence 只能说明证据缺失，不能支撑事实结论。
                - TOOL_DUPLICATE_SKIPPED 和 TOOL_BUDGET_EXCEEDED 是策略拦截，不是外部工具失败；不要重复调用同一方向，直接记录证据缺口并结束该方向。
                - 最终报告需要包含“置信度”和“缺失证据”判断。
                """.trim();
    }

    private String cell(String value) {
        return value(value).replace("|", "\\|").replace("\n", " ");
    }

    private String compact(String value, int limit) {
        String text = value(value).replaceAll("\\s+", " ").trim();
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    public record QualityAssessment(int score, String grade, String summary, List<String> issues) {
    }

    private record EvidenceReferenceContext(List<DiagnosisEvidence> toolEvidence,
                                            List<DiagnosisEvidence> usableEvidence,
                                            Map<String, DiagnosisEvidence> allEvidenceById,
                                            Map<String, DiagnosisEvidence> usableEvidenceById,
                                            Set<String> citedIds,
                                            List<String> unknownIds,
                                            List<String> invalidIds,
                                            Set<String> knownCitedIds) {
    }
}
