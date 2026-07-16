package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.QueryMetricsTools;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class MetricTrendPrefetchService {

    private static final Logger logger = LoggerFactory.getLogger(MetricTrendPrefetchService.class);
    private static final List<String> PREFETCH_WINDOWS = List.of("15m", "1h");

    private final QueryMetricsTools queryMetricsTools;
    private final DiagnosisEvidenceRecorder diagnosisEvidenceRecorder;
    private final Executor prefetchExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetricTrendPrefetchService(@Nullable QueryMetricsTools queryMetricsTools,
                                      @Nullable DiagnosisEvidenceRecorder diagnosisEvidenceRecorder) {
        this(queryMetricsTools, diagnosisEvidenceRecorder, Runnable::run);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MetricTrendPrefetchService(@Nullable QueryMetricsTools queryMetricsTools,
                                      @Nullable DiagnosisEvidenceRecorder diagnosisEvidenceRecorder,
                                      @Qualifier("diagnosisPrefetchExecutor") @Nullable Executor prefetchExecutor) {
        this.queryMetricsTools = queryMetricsTools;
        this.diagnosisEvidenceRecorder = diagnosisEvidenceRecorder;
        this.prefetchExecutor = prefetchExecutor == null ? Runnable::run : prefetchExecutor;
    }

    public String prefetchAndAppend(IncidentRecord incident, DiagnosisRunRecord run, String alertContext) {
        Set<MetricTarget> targets = inferMetricTargets(incident);
        if (targets.isEmpty() || queryMetricsTools == null) {
            return alertContext;
        }

        List<CompletableFuture<TrendObservation>> futures = new ArrayList<>();
        for (MetricTarget target : targets) {
            for (String window : PREFETCH_WINDOWS) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> queryTrend(incident, run, target, window), prefetchExecutor));
            }
        }
        List<TrendObservation> observations = futures.stream().map(CompletableFuture::join).toList();

        if (observations.isEmpty()) {
            return alertContext;
        }
        return alertContext + "\n\n" + buildContextBlock(observations);
    }

    private TrendObservation queryTrend(IncidentRecord incident,
                                        DiagnosisRunRecord run,
                                        MetricTarget target,
                                        String window) {
        try {
            Optional<DiagnosisEvidence> existing = findSuccessfulEvidence(run, target, window);
            if (existing.isPresent()) {
                DiagnosisEvidence evidence = existing.get();
                return new TrendObservation(target, window, true, evidence.getId(),
                        evidence.getSummary(), null);
            }
            String result;
            if (diagnosisEvidenceRecorder != null && notBlank(incident.getId()) && notBlank(run.getRunId())) {
                result = diagnosisEvidenceRecorder.withRun(incident.getId(), run.getRunId(),
                        () -> queryMetricsTools.queryMetricTrend(
                                target.metric(), target.service(), target.instance(), window, null));
            } else {
                result = queryMetricsTools.queryMetricTrend(
                        target.metric(), target.service(), target.instance(), window, null);
            }
            return parseObservation(target, window, result, null);
        } catch (Exception e) {
            logger.warn("预取指标趋势失败, metric: {}, service: {}, instance: {}, window: {}",
                    target.metric(), target.service(), target.instance(), window, e);
            return parseObservation(target, window, null, e.getMessage());
        }
    }

    private Optional<DiagnosisEvidence> findSuccessfulEvidence(DiagnosisRunRecord run,
                                                               MetricTarget target,
                                                               String window) {
        if (run == null || run.getEvidence() == null) {
            return Optional.empty();
        }
        return run.getEvidence().stream()
                .filter(evidence -> evidence != null && evidence.isSuccess())
                .filter(evidence -> QueryMetricsTools.TOOL_QUERY_METRIC_TREND.equals(evidence.getToolName()))
                .filter(evidence -> matchesMetricQuery(evidence.getQueryParams(), target, window))
                .findFirst();
    }

    private boolean matchesMetricQuery(String queryParams, MetricTarget target, String window) {
        try {
            JsonNode query = objectMapper.readTree(queryParams == null ? "{}" : queryParams);
            return target.metric().equals(query.path("metric").asText())
                    && target.service().equals(query.path("service").asText())
                    && target.instance().equals(query.path("instance").asText())
                    && window.equals(query.path("window").asText());
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private TrendObservation parseObservation(MetricTarget target, String window, String result, String error) {
        if (result == null || result.isBlank()) {
            return new TrendObservation(target, window, false, null, null,
                    error == null || error.isBlank() ? "趋势查询未返回内容" : error);
        }
        try {
            JsonNode json = objectMapper.readTree(result);
            boolean success = json.path("success").asBoolean(false);
            String message = json.path("message").asText("");
            String evidenceId = json.path("_diagnosisEvidenceId").asText(null);
            String summary = message.isBlank() ? json.path("summary").toString() : message;
            String errorMessage = json.path("error").asText(error);
            return new TrendObservation(target, window, success, evidenceId, summary, errorMessage);
        } catch (Exception e) {
            return new TrendObservation(target, window, false, null, result, e.getMessage());
        }
    }

    private String buildContextBlock(List<TrendObservation> observations) {
        StringBuilder builder = new StringBuilder("## 预取指标趋势证据\n");
        for (TrendObservation observation : observations) {
            MetricTarget target = observation.target();
            builder.append("- metric=").append(target.metric())
                    .append(", window=").append(observation.window())
                    .append(", service=").append(defaultText(target.service(), "unknown"))
                    .append(", instance=").append(defaultText(target.instance(), "unknown"))
                    .append(", success=").append(observation.success());
            if (notBlank(observation.evidenceId())) {
                builder.append(", evidence=[evidence: ").append(observation.evidenceId()).append(']');
            } else {
                builder.append(", evidence=缺失");
            }
            String detail = observation.success() ? observation.summary() : observation.error();
            if (notBlank(detail)) {
                builder.append(", summary=").append(detail);
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private Set<MetricTarget> inferMetricTargets(IncidentRecord incident) {
        Set<MetricTarget> targets = new LinkedHashSet<>();
        if (incident == null || incident.getAlertPayloads() == null) {
            return targets;
        }

        for (AlertPayload payload : incident.getAlertPayloads()) {
            if (payload.getAlerts() == null) {
                continue;
            }
            for (AlertPayload.Alert alert : payload.getAlerts()) {
                Set<String> metrics = inferMetrics(payload, alert);
                for (String metric : metrics) {
                    targets.add(new MetricTarget(metric, service(payload, alert), instance(alert)));
                }
            }
        }
        return targets;
    }

    private Set<String> inferMetrics(AlertPayload payload, AlertPayload.Alert alert) {
        String text = String.join(" ",
                value(alert.getLabels(), "alertname"),
                value(alert.getLabels(), "service"),
                value(alert.getLabels(), "job"),
                value(alert.getAnnotations(), "summary"),
                value(alert.getAnnotations(), "description"),
                value(payload.getCommonAnnotations(), "summary"),
                value(payload.getCommonAnnotations(), "description")
        ).toLowerCase(Locale.ROOT);

        Set<String> metrics = new LinkedHashSet<>();
        if (containsAny(text, "cpu", "highcpu", "cpu 使用率", "cpu使用率")) {
            metrics.add("cpu_usage");
        }
        if (containsAny(text, "memory", "mem", "内存", "oom")) {
            metrics.add("memory_usage");
        }
        if (containsAny(text, "error", "errors", "5xx", "5..", "错误率", "失败率")) {
            metrics.add("error_rate");
        }
        if (containsAny(text, "p99", "latency", "slowresponse", "响应时间", "延迟")) {
            metrics.add("p99_latency");
        }
        if (containsAny(text, "restart", "restarts", "crashloop", "oomkilled", "重启")) {
            metrics.add("restart_count");
        }
        return metrics;
    }

    private String service(AlertPayload payload, AlertPayload.Alert alert) {
        String service = firstNonBlank(
                value(alert.getLabels(), "service"),
                value(alert.getLabels(), "job"),
                value(payload.getCommonLabels(), "service"),
                value(payload.getCommonLabels(), "job")
        );
        return service == null ? "" : service;
    }

    private String instance(AlertPayload.Alert alert) {
        String instance = firstNonBlank(
                value(alert.getLabels(), "instance"),
                value(alert.getLabels(), "pod"),
                value(alert.getLabels(), "pod_name")
        );
        return instance == null ? "" : instance;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String value(Map<String, String> map, String key) {
        if (map == null || key == null) {
            return "";
        }
        return map.getOrDefault(key, "");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String defaultText(String value, String fallback) {
        return notBlank(value) ? value : fallback;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record MetricTarget(String metric, String service, String instance) {
    }

    private record TrendObservation(MetricTarget target,
                                    String window,
                                    boolean success,
                                    String evidenceId,
                                    String summary,
                                    String error) {
    }
}
