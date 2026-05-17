package org.example.service;

import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DiagnosisReportService {

    private static final Pattern EVIDENCE_REF_PATTERN = Pattern.compile("\\[evidence:\\s*([^\\]\\s]+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final int SUMMARY_LIMIT = 180;

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
        List<DiagnosisEvidence> toolEvidence = toolEvidence(evidence);
        if (toolEvidence.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("## 可用工具证据表\n");
        builder.append("| Evidence ID | 类型 | 工具 | 时间范围 | 状态 | 摘要 |\n");
        builder.append("|---|---|---|---|---|---|\n");
        for (DiagnosisEvidence item : toolEvidence) {
            builder.append("| ")
                    .append(cell(item.getId()))
                    .append(" | ")
                    .append(cell(item.getType()))
                    .append(" | ")
                    .append(cell(item.getToolName()))
                    .append(" | ")
                    .append(cell(item.getTimeRange()))
                    .append(" | ")
                    .append(item.isSuccess() ? "success" : "failed")
                    .append(" | ")
                    .append(cell(compact(item.getSummary(), SUMMARY_LIMIT)))
                    .append(" |\n");
        }
        return builder.toString().trim();
    }

    public String guardReport(String report, DiagnosisRunRecord run) {
        return constrainReport(report, run == null ? List.of() : run.getEvidence());
    }

    public String constrainReport(String report, List<DiagnosisEvidence> evidence) {
        String safeReport = report == null ? "" : report.trim();
        if (safeReport.contains("## 证据校验")) {
            return safeReport;
        }

        List<DiagnosisEvidence> toolEvidence = toolEvidence(evidence);
        Map<String, DiagnosisEvidence> evidenceById = new LinkedHashMap<>();
        for (DiagnosisEvidence item : toolEvidence) {
            if (notBlank(item.getId())) {
                evidenceById.put(item.getId(), item);
            }
        }

        Set<String> citedIds = extractEvidenceIds(safeReport);
        List<String> unknownIds = citedIds.stream()
                .filter(id -> !evidenceById.containsKey(id))
                .toList();
        Set<String> knownCitedIds = new LinkedHashSet<>(citedIds);
        knownCitedIds.removeAll(unknownIds);

        List<String> missingEvidence = findMissingEvidence(safeReport, evidenceById, knownCitedIds, toolEvidence);
        String confidence = confidence(unknownIds, missingEvidence, knownCitedIds);
        String status = validationStatus(unknownIds, missingEvidence);

        return safeReport
                + "\n\n---\n\n"
                + "## 证据校验\n"
                + "- 校验状态: " + status + "\n"
                + "- 置信度: " + confidence + "\n"
                + "- 已引用证据: " + (knownCitedIds.isEmpty() ? "无" : String.join(", ", knownCitedIds)) + "\n"
                + "- 未知证据: " + (unknownIds.isEmpty() ? "无" : String.join(", ", unknownIds)) + "\n"
                + "- 缺失证据: " + (missingEvidence.isEmpty() ? "无" : String.join("; ", missingEvidence)) + "\n"
                + "- 可用证据: " + availableEvidenceSummary(toolEvidence) + "\n"
                + "- 约束说明: 以上校验仅基于已持久化的工具 evidence；未被 evidence 支撑的结论应按证据不足处理。\n";
    }

    private List<String> findMissingEvidence(String report,
                                             Map<String, DiagnosisEvidence> evidenceById,
                                             Set<String> knownCitedIds,
                                             List<DiagnosisEvidence> toolEvidence) {
        List<String> missing = new ArrayList<>();
        if (!toolEvidence.isEmpty() && knownCitedIds.isEmpty()) {
            missing.add("报告没有引用任何工具 evidence id");
        }
        if (containsResourceClaim(report) && !hasCitedTool(knownCitedIds, evidenceById, "queryMetricTrend")) {
            missing.add("资源类结论缺少 queryMetricTrend 趋势 evidence");
        }
        if (containsLogClaim(report) && !hasCitedTool(knownCitedIds, evidenceById, "queryLogs")) {
            missing.add("日志/异常结论缺少 queryLogs evidence");
        }
        if (containsGcClaim(report) && !hasCitedEvidenceText(knownCitedIds, evidenceById, "GC", "Full GC", "gc_", "OutOfMemory", "OOM")) {
            missing.add("GC/Full GC 结论缺少对应日志或 JVM 指标 evidence");
        }
        return missing;
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

    private boolean hasCitedTool(Set<String> citedIds, Map<String, DiagnosisEvidence> evidenceById, String toolName) {
        return citedIds.stream()
                .map(evidenceById::get)
                .anyMatch(evidence -> evidence != null && toolName.equals(evidence.getToolName()));
    }

    private boolean hasCitedEvidenceText(Set<String> citedIds,
                                         Map<String, DiagnosisEvidence> evidenceById,
                                         String... needles) {
        return citedIds.stream()
                .map(evidenceById::get)
                .filter(evidence -> evidence != null)
                .map(evidence -> String.join(" ",
                        value(evidence.getToolName()),
                        value(evidence.getSummary()),
                        value(evidence.getContent()),
                        value(evidence.getRawFragment()),
                        value(evidence.getQueryParams())))
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

    private boolean containsAny(String text, String... needles) {
        String safeText = text == null ? "" : text;
        for (String needle : needles) {
            if (safeText.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String confidence(List<String> unknownIds, List<String> missingEvidence, Set<String> knownCitedIds) {
        if (!unknownIds.isEmpty()) {
            return "低";
        }
        if (missingEvidence.isEmpty() && !knownCitedIds.isEmpty()) {
            return "高";
        }
        return "中";
    }

    private String validationStatus(List<String> unknownIds, List<String> missingEvidence) {
        if (!unknownIds.isEmpty()) {
            return "失败";
        }
        if (!missingEvidence.isEmpty()) {
            return "需补证";
        }
        return "通过";
    }

    private String availableEvidenceSummary(List<DiagnosisEvidence> toolEvidence) {
        if (toolEvidence.isEmpty()) {
            return "无";
        }
        return toolEvidence.stream()
                .map(evidence -> value(evidence.getId()) + "(" + value(evidence.getToolName()) + ")")
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

    private String reportRules() {
        return """
                ## 报告证据约束
                - 最终报告中的每个根因、症状和处理建议必须引用上方 Evidence ID，格式为 [evidence: ev-xxxx]。
                - 资源类结论（CPU、内存、错误率、P99、重启）必须引用 queryMetricTrend 证据。
                - 日志、异常、GC/Full GC、OOM 结论必须引用 queryLogs 或明确包含该信号的 JVM/日志证据。
                - 禁止使用未出现在证据表中的 evidence id；证据不足时必须写“证据不足”，不能补写不存在的数据。
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
}
