package org.example.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Defines the minimum grounding contract for answers that rely on the internal
 * knowledge base.
 *
 * <p>This is a deterministic format gate, not a claim that a citation proves
 * the answer is correct. The cited identifier still has to be supplied by
 * {@code queryInternalDocs}; the model is instructed not to invent identifiers.</p>
 */
public final class RagGroundingPolicy {

    private static final Pattern SOURCE_CITATION = Pattern.compile(
            "\\[(?:来源|source):(?!\\s*<id>\\s*\\])\\s*[^\\]\\r\\n]+\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_EVIDENCE = Pattern.compile(
            "(?:(?:没有找到|未找到|无法从|没有足够|证据不足).{0,30}(?:知识库|内部文档|文档|资料|证据)"
                    + "|(?:知识库|内部文档|文档|资料|证据).{0,30}(?:没有找到|未找到|无法从|没有足够|证据不足))",
            Pattern.CASE_INSENSITIVE);
    private static final String FALLBACK =
            "当前回答未包含可核验的内部知识库来源，因此不输出未经引用的内部事实。"
                    + "请重试，或换一种方式描述要查询的内部流程、文档或技术指南。";

    public boolean requiresCitation(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "内部", "知识库", "最佳实践", "技术指南", "runbook",
                "internal", "knowledge base", "knowledgebase")) {
            return true;
        }
        return containsAny(normalized, "文档", "流程")
                && containsAny(normalized,
                "查询", "查找", "检索", "根据", "中的", "里面", "怎么", "如何", "是什么",
                "query", "search", "what", "how", "where");
    }

    public boolean hasCitation(String answer) {
        return answer != null && SOURCE_CITATION.matcher(answer).find();
    }

    public Set<String> citationIds(String answer) {
        if (answer == null || answer.isBlank()) {
            return Set.of();
        }
        Set<String> ids = new java.util.LinkedHashSet<>();
        Matcher matcher = SOURCE_CITATION.matcher(answer);
        while (matcher.find()) {
            String citation = matcher.group();
            int colon = citation.indexOf(':');
            int closing = citation.lastIndexOf(']');
            if (colon >= 0 && closing > colon) {
                ids.add(citation.substring(colon + 1, closing).trim());
            }
        }
        return Set.copyOf(ids);
    }

    public boolean explicitlyReportsNoEvidence(String answer) {
        return answer != null && NO_EVIDENCE.matcher(answer).find();
    }

    /** Rejects an uncited, non-empty answer to a knowledge-base question. */
    public String enforce(String question, String answer) {
        if (!requiresCitation(question) || answer == null || answer.isBlank()
                || hasCitation(answer) || explicitlyReportsNoEvidence(answer)) {
            return answer;
        }
        return FALLBACK;
    }

    /** Rejects citations that were not present in the current retrieval result set. */
    public String enforce(String question, String answer, Set<String> knownSourceIds) {
        String grounded = enforce(question, answer);
        if (!requiresCitation(question) || grounded == null || grounded.isBlank()
                || explicitlyReportsNoEvidence(grounded) || grounded.equals(FALLBACK)) {
            return grounded;
        }
        Set<String> known = knownSourceIds == null ? Set.of() : knownSourceIds;
        return known.containsAll(citationIds(grounded)) && !citationIds(grounded).isEmpty()
                ? grounded : FALLBACK;
    }

    public String fallbackMessage() {
        return FALLBACK;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
