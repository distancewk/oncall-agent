package org.example.service;

import org.example.dto.DiagnosisEvidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the bounded, deterministic claim-to-evidence contract used by the
 * online report gate. Unsupported open-domain claims fail closed instead of
 * being presented as high-confidence conclusions.
 */
public final class ClaimSupportService {

    private static final Pattern EVIDENCE_REF = Pattern.compile(
            "\\[evidence:\\s*([^\\]\\s]+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION = Pattern.compile(
            "(?ms)^## (告警根因分析|处理方案执行|结论)\\s*$.*?(?=^## |\\z)");
    private static final Set<String> METRIC_TERMS = Set.of(
            "cpu", "内存", "memory", "错误率", "error_rate", "p99", "延迟", "latency",
            "restart", "重启", "gc", "吞吐", "qps");
    private static final Set<String> LOG_TERMS = Set.of(
            "日志", "log", "exception", "异常", "stack", "堆栈", "oom", "oomkilled", "crashloop");
    private static final Pattern METRIC_FIELD = Pattern.compile(
            "\"metric\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern WINDOW_FIELD = Pattern.compile(
            "\"window\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT_VALUE = Pattern.compile(
            "(?<![A-Za-z0-9])\\d+(?:\\.\\d+)?\\s*%", Pattern.CASE_INSENSITIVE);
    private static final Set<String> INCREASING_TERMS = Set.of(
            "上升", "升高", "增长", "上涨", "持续增加", "increasing", "increase");
    private static final Set<String> DECREASING_TERMS = Set.of(
            "下降", "降低", "减少", "下跌", "decreasing", "decrease");
    private static final Set<String> SPIKE_TERMS = Set.of(
            "突增", "骤升", "spike", "spiking");

    private final EvidenceEntailmentPolicy anchorPolicy = new EvidenceEntailmentPolicy();

    public List<String> findUnsupportedClaims(String report,
                                               Map<String, DiagnosisEvidence> usableEvidenceById) {
        LinkedHashSet<String> unsupported = new LinkedHashSet<>(
                anchorPolicy.findUnsupportedClaims(report, usableEvidenceById));
        if (report == null || usableEvidenceById == null || usableEvidenceById.isEmpty()) {
            return List.copyOf(unsupported);
        }
        Matcher sectionMatcher = SECTION.matcher(report);
        while (sectionMatcher.find()) {
            for (String line : sectionMatcher.group().split("\\R")) {
                String claim = line.trim();
                if (claim.isBlank() || isUncertainty(claim)) {
                    continue;
                }
                Matcher refMatcher = EVIDENCE_REF.matcher(claim);
                List<DiagnosisEvidence> references = new ArrayList<>();
                while (refMatcher.find()) {
                    DiagnosisEvidence evidence = usableEvidenceById.get(refMatcher.group(1).trim());
                    if (evidence != null) {
                        references.add(evidence);
                    }
                }
                if (references.isEmpty()) {
                    continue;
                }
                String normalized = claim.replaceAll(EVIDENCE_REF.pattern(), "")
                        .toLowerCase(Locale.ROOT);
                if (containsAny(normalized, LOG_TERMS)
                        && references.stream().noneMatch(e -> "queryLogs".equals(e.getToolName())
                        || hasJvmMetric(e))) {
                    unsupported.add(compact(claim));
                } else if (containsAny(normalized, METRIC_TERMS)
                        && references.stream().noneMatch(e -> supportsMetricClaim(normalized, e))) {
                    unsupported.add(compact(claim));
                }
            }
        }
        return List.copyOf(unsupported);
    }

    private boolean hasJvmMetric(DiagnosisEvidence evidence) {
        return "queryMetricTrend".equals(evidence.getToolName())
                && value(evidence.getQueryParams()).contains("jvm_gc_collection_seconds_count");
    }

    private boolean supportsMetricClaim(String claim, DiagnosisEvidence evidence) {
        if (!"queryMetricTrend".equals(evidence.getToolName())) {
            return false;
        }
        String queryParams = value(evidence.getQueryParams());
        String metric = firstGroup(METRIC_FIELD, queryParams);
        if (metric.isBlank()) {
            return false;
        }
        String normalizedClaim = claim.toLowerCase(Locale.ROOT);
        String normalizedMetric = metric.toLowerCase(Locale.ROOT);
        if (!metricMentioned(normalizedClaim, normalizedMetric)) {
            return false;
        }

        String evidenceText = String.join(" ", queryParams, value(evidence.getTimeRange()),
                value(evidence.getSummary()), value(evidence.getContent()), value(evidence.getRawFragment()))
                .toLowerCase(Locale.ROOT);
        String queryWindow = firstGroup(WINDOW_FIELD, queryParams);
        if (!queryWindow.isBlank() && containsExplicitWindow(normalizedClaim)
                && !normalizedClaim.contains(queryWindow.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (!directionSupported(normalizedClaim, evidenceText)) {
            return false;
        }
        Matcher percentage = PERCENT_VALUE.matcher(normalizedClaim);
        while (percentage.find()) {
            if (!evidenceText.contains(percentage.group().replaceAll("\\s+", ""))) {
                return false;
            }
        }
        return true;
    }

    private boolean metricMentioned(String claim, String metric) {
        if (claim.contains(metric)) {
            return true;
        }
        return switch (metric) {
            case "cpu_usage" -> claim.contains("cpu");
            case "memory_usage" -> claim.contains("memory") || claim.contains("内存");
            case "error_rate" -> claim.contains("error") || claim.contains("错误");
            case "p99_latency" -> claim.contains("p99") || claim.contains("latency") || claim.contains("延迟");
            case "restart_count" -> claim.contains("restart") || claim.contains("重启");
            case "jvm_gc_collection_seconds_count" -> claim.contains("gc") || claim.contains("垃圾回收");
            default -> false;
        };
    }

    private boolean directionSupported(String claim, String evidence) {
        Set<String> required = new LinkedHashSet<>();
        if (containsAny(claim, INCREASING_TERMS)) {
            required.add("increasing");
        }
        if (containsAny(claim, DECREASING_TERMS)) {
            required.add("decreasing");
        }
        if (containsAny(claim, SPIKE_TERMS)) {
            required.add("spiking");
        }
        if (required.isEmpty()) {
            return true;
        }
        return required.stream().allMatch(direction -> switch (direction) {
            case "increasing" -> containsAny(evidence, INCREASING_TERMS);
            case "decreasing" -> containsAny(evidence, DECREASING_TERMS);
            case "spiking" -> containsAny(evidence, SPIKE_TERMS);
            default -> false;
        });
    }

    private boolean containsExplicitWindow(String claim) {
        return claim.matches(".*\\b\\d+\\s*(?:s|m|h|d)\\b.*")
                || claim.matches(".*最近\\s*\\d+.*");
    }

    private String firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean containsAny(String value, Set<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }

    private boolean isUncertainty(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        return normalized.contains("证据不足") || normalized.contains("无法确认")
                || normalized.contains("人工复核") || normalized.contains("暂不确认")
                || normalized.contains("待补充证据");
    }

    private String compact(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "…";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

}
