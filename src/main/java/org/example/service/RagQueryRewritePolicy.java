package org.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic retrieval-only query expansion.
 *
 * <p>The original question is preserved and only a bounded set of operational
 * aliases is appended. This improves sparse retrieval for mixed Chinese and
 * metric/English terminology without asking a model to rewrite user intent.</p>
 */
public final class RagQueryRewritePolicy {

    private static final List<Alias> ALIASES = List.of(
            new Alias(List.of("cpu", "处理器"), "cpu_usage cpu utilization"),
            new Alias(List.of("内存", "oom", "out of memory"), "memory memory_usage oom"),
            new Alias(List.of("错误率", "报错"), "error_rate errors"),
            new Alias(List.of("延迟", "p99", "响应时间"), "latency p99 response time"),
            new Alias(List.of("依赖", "超时"), "dependency timeout upstream"),
            new Alias(List.of("数据库", "连接池"), "database connection pool"),
            new Alias(List.of("慢查询", "慢 sql"), "slow sql query"),
            new Alias(List.of("重启", "拉起"), "restart deployment"),
            new Alias(List.of("发布", "回滚"), "deploy rollback release"),
            new Alias(List.of("流程", "runbook"), "runbook procedure")
    );

    public String rewrite(String query) {
        if (query == null || query.isBlank()) {
            return query == null ? "" : query.trim();
        }
        String normalized = query.trim().replaceAll("\\s+", " ");
        String lower = normalized.toLowerCase(Locale.ROOT);
        List<String> expansions = new ArrayList<>();
        for (Alias alias : ALIASES) {
            if (alias.triggers().stream().anyMatch(lower::contains)
                    && java.util.Arrays.stream(alias.expansion()
                    .toLowerCase(Locale.ROOT).split("\\s+"))
                    .filter(token -> token.length() >= 4
                            || token.chars().anyMatch(Character::isDigit))
                    .noneMatch(token -> containsTechnicalToken(lower, token))) {
                expansions.add(alias.expansion());
            }
        }
        return expansions.isEmpty()
                ? normalized
                : normalized + " " + String.join(" ", expansions);
    }

    private boolean containsTechnicalToken(String query, String token) {
        return query.matches(".*(?<![a-z0-9_])" + java.util.regex.Pattern.quote(token)
                + "(?![a-z0-9_]).*");
    }

    private record Alias(List<String> triggers, String expansion) {
    }
}
