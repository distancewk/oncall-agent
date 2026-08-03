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
 * Conservative, deterministic evidence-to-claim support check.
 *
 * <p>This is intentionally not presented as a full natural-language entailment
 * model. It only rejects a cited factual line when none of its observable
 * anchors (metric names, identifiers, numbers, or meaningful Chinese phrases)
 * occurs in the cited successful evidence summary/query. The resulting false
 * negatives are safer than accepting a citation that is visibly unrelated;
 * expert-labelled offline evaluation remains the authority for correctness.</p>
 */
public final class EvidenceEntailmentPolicy {

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_.:-]{1,}|\\d+(?:\\.\\d+)?|[\\p{IsHan}]{2,}");
    private static final Pattern EVIDENCE_REF_PATTERN = Pattern.compile(
            "\\[evidence:\\s*([^\\]\\s]+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(?ms)^## (告警根因分析|处理方案执行|结论)\\s*$.*?(?=^## |\\z)");
    private static final Set<String> GENERIC_ANCHORS = Set.of(
            "证据", "报告", "当前", "建议", "查询", "显示", "存在", "情况", "可以",
            "指标", "日志", "成功", "失败", "通过", "需要", "系统", "服务", "进行");

    public List<String> findUnsupportedClaims(String report,
                                               Map<String, DiagnosisEvidence> usableEvidenceById) {
        List<String> unsupported = new ArrayList<>();
        if (report == null || report.isBlank() || usableEvidenceById == null
                || usableEvidenceById.isEmpty()) {
            return unsupported;
        }
        Matcher sectionMatcher = SECTION_PATTERN.matcher(report);
        while (sectionMatcher.find()) {
            for (String line : sectionMatcher.group().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isBlank() || isUncertaintyStatement(trimmed)
                        || trimmed.startsWith("|") || trimmed.startsWith("###")) {
                    continue;
                }
                Matcher referenceMatcher = EVIDENCE_REF_PATTERN.matcher(trimmed);
                List<String> references = new ArrayList<>();
                while (referenceMatcher.find()) {
                    references.add(referenceMatcher.group(1).trim());
                }
                if (references.isEmpty()) {
                    continue;
                }
                String claim = EVIDENCE_REF_PATTERN.matcher(trimmed).replaceAll("").trim();
                boolean supported = references.stream()
                        .map(usableEvidenceById::get)
                        .filter(evidence -> evidence != null)
                        .anyMatch(evidence -> supports(claim, evidence));
                if (!supported) {
                    unsupported.add(compact(trimmed, 160));
                }
            }
        }
        return List.copyOf(unsupported);
    }

    private boolean supports(String claim, DiagnosisEvidence evidence) {
        Set<String> claimAnchors = anchors(claim);
        Set<String> evidenceAnchors = anchors(String.join(" ",
                value(evidence.getToolName()),
                value(evidence.getTimeRange()),
                value(evidence.getSummary()),
                value(evidence.getContent()),
                value(evidence.getQueryParams())));
        if (claimAnchors.isEmpty() || evidenceAnchors.isEmpty()) {
            return claimAnchors.isEmpty();
        }
        long matchingAnchors = claimAnchors.stream()
                .filter(evidenceAnchors::contains)
                .count();
        return matchingAnchors >= Math.min(2, claimAnchors.size());
    }

    private Set<String> anchors(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = ANCHOR_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 2 && !GENERIC_ANCHORS.contains(token)) {
                result.add(token);
                if (token.matches("[a-z][a-z0-9_.:-]*")) {
                    for (String part : token.split("[_.:-]+")) {
                        if (part.length() >= 2) {
                            result.add(part);
                        }
                    }
                }
            }
        }
        return result;
    }

    private boolean isUncertaintyStatement(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        return normalized.contains("证据不足") || normalized.contains("无法确认")
                || normalized.contains("暂不确认") || normalized.contains("人工复核")
                || normalized.contains("待补充证据") || normalized.contains("未能确认");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String compact(String value, int limit) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }
}
