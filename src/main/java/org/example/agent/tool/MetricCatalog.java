package org.example.agent.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for metrics accepted by the diagnosis tool and by the agents.
 */
public final class MetricCatalog {

    private static final Map<String, String> METRICS;

    static {
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("cpu_usage", "CPU 使用率");
        metrics.put("memory_usage", "内存使用率");
        metrics.put("error_rate", "错误率");
        metrics.put("p99_latency", "P99 延迟");
        metrics.put("restart_count", "容器重启次数");
        metrics.put("jvm_gc_collection_seconds_count", "JVM GC 次数");
        METRICS = Collections.unmodifiableMap(metrics);
    }

    private MetricCatalog() {
    }

    public static boolean isSupported(String metric) {
        return metric != null && METRICS.containsKey(normalize(metric));
    }

    public static String normalize(String metric) {
        return metric == null ? "" : metric.trim().toLowerCase(Locale.ROOT);
    }

    public static Set<String> names() {
        return METRICS.keySet();
    }

    public static String description(String metric) {
        return METRICS.getOrDefault(normalize(metric), "未知指标");
    }

    public static String supportedNamesText() {
        return String.join(", ", METRICS.keySet());
    }
}
