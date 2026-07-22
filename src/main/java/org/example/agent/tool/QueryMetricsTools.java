package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.exception.DependencyUnavailableException;
import org.example.service.DependencyGuard;
import org.example.service.DependencyGuardExecutor;
import org.example.service.DiagnosisEvidenceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Prometheus 告警和指标查询工具
 * 用于查询 Prometheus 的活动告警与核心指标趋势
 */
@Component
public class QueryMetricsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryMetricsTools.class);
    private static final Set<String> SUPPORTED_TREND_WINDOWS = Set.of("15m", "1h", "6h");
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_PROMETHEUS_ALERTS = "queryPrometheusAlerts";
    public static final String TOOL_QUERY_METRIC_TREND = "queryMetricTrend";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${prometheus.base-url}")
    private String prometheusBaseUrl;
    
    @Value("${prometheus.timeout:10}")
    private int timeout;
    
    @Value("${prometheus.mock-enabled:false}")
    private boolean mockEnabled;
    
    @org.springframework.beans.factory.annotation.Autowired
    private OkHttpClient httpClient;

    @Autowired(required = false)
    private DiagnosisEvidenceRecorder diagnosisEvidenceRecorder;

    @Autowired(required = false)
    private DependencyGuard dependencyGuard;
    
    @jakarta.annotation.PostConstruct
    public void init() {
        logger.info("✅ QueryMetricsTools 初始化成功, Prometheus URL: {}, Mock模式: {}", prometheusBaseUrl, mockEnabled);
    }
    
    /**
     * 查询 Prometheus 活动告警
     * 该工具从 Prometheus 告警系统检索所有当前活动/触发的告警，包括标签、注释、状态和值
     */
    @Tool(description = "Query active alerts from Prometheus alerting system. " +
            "This tool retrieves all currently active/firing alerts including their labels, annotations, state, and values. " +
            "Use this tool when you need to check what alerts are currently firing, investigate alert conditions, or monitor alert status.")
    public String queryPrometheusAlerts() {
        if (diagnosisEvidenceRecorder != null) {
            return diagnosisEvidenceRecorder.recordToolCall(
                    TOOL_QUERY_PROMETHEUS_ALERTS,
                    "{\"endpoint\":\"/api/v1/alerts\"}",
                    "active alerts",
                    this::doQueryPrometheusAlerts);
        }
        return doQueryPrometheusAlerts();
    }

    /**
     * 查询核心运维指标趋势。
     * 支持 CPU、内存、错误率、P99 延迟和重启次数，用于诊断时补齐时间序列证据。
     */
    @Tool(description = "Query Prometheus metric trend over a time window. " +
            "Supported metrics: cpu_usage, memory_usage, error_rate, p99_latency, restart_count, " +
            "jvm_gc_collection_seconds_count. " +
            "Use this before diagnosing CPU, memory, latency, error-rate, or restart alerts. " +
            "Returns points, PromQL query, min/max/avg/latest, direction, anomaly flag, and message.")
    public String queryMetricTrend(
            @ToolParam(description = "Metric name. Supported: cpu_usage, memory_usage, error_rate, p99_latency, restart_count, jvm_gc_collection_seconds_count")
            String metric,
            @ToolParam(description = "Service name, for example payment-service. Optional but recommended")
            String service,
            @ToolParam(description = "Instance or pod name, for example pod-payment-service-xxx. Optional")
            String instance,
            @ToolParam(description = "Trend window. Supported: 15m, 1h, 6h. Invalid or blank values default to 1h")
            String window,
            @ToolParam(description = "Prometheus query_range step, for example 30s, 1m, 5m. Blank uses the default for the selected window")
            String step) {
        String normalizedMetric = normalizeMetric(metric);
        String normalizedWindow = normalizeWindow(window);
        String normalizedStep = normalizeStep(normalizedWindow, step);
        String queryParams = buildTrendQueryParams(normalizedMetric, service, instance, normalizedWindow, normalizedStep);

        if (diagnosisEvidenceRecorder != null) {
            return diagnosisEvidenceRecorder.recordToolCall(
                    TOOL_QUERY_METRIC_TREND,
                    queryParams,
                    normalizedWindow,
                    () -> doQueryMetricTrend(normalizedMetric, service, instance, normalizedWindow, normalizedStep));
        }
        return doQueryMetricTrend(normalizedMetric, service, instance, normalizedWindow, normalizedStep);
    }

    private String doQueryPrometheusAlerts() {
        logger.info("开始查询 Prometheus 活动告警, Mock模式: {}", mockEnabled);
        
        try {
            List<SimplifiedAlert> simplifiedAlerts;
            
            if (mockEnabled) {
                // Mock 模式：返回与文档关联的模拟告警数据
                simplifiedAlerts = buildMockAlerts();
                logger.info("使用 Mock 数据，返回 {} 个模拟告警", simplifiedAlerts.size());
            } else {
                // 真实模式：调用 Prometheus Alerts API
                PrometheusAlertsResult result = fetchPrometheusAlerts();
                
                if (!"success".equals(result.getStatus())) {
                    return buildErrorResponse("Prometheus API 返回非成功状态: " + result.getStatus(),
                            result.getError(), "DEPENDENCY_ERROR");
                }
                
                // 转换为简化格式，对于相同的 alertname，只保留第一个
                Set<String> seenAlertNames = new HashSet<>();
                simplifiedAlerts = new ArrayList<>();
                
                for (PrometheusAlert alert : result.getData().getAlerts()) {
                    String alertName = alert.getLabels().get("alertname");
                    
                    // 如果这个 alertname 已经存在，跳过
                    if (seenAlertNames.contains(alertName)) {
                        continue;
                    }
                    
                    // 标记为已见过
                    seenAlertNames.add(alertName);
                    
                    SimplifiedAlert simplified = new SimplifiedAlert();
                    simplified.setAlertName(alertName);
                    simplified.setDescription(alert.getAnnotations().getOrDefault("description", ""));
                    simplified.setState(alert.getState());
                    simplified.setActiveAt(alert.getActiveAt());
                    simplified.setDuration(calculateDuration(alert.getActiveAt()));
                    
                    simplifiedAlerts.add(simplified);
                }
            }
            
            // 构建成功响应
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(true);
            output.setDataMode(mockEnabled ? "MOCK" : "REAL");
            output.setAlerts(simplifiedAlerts);
            output.setMessage(String.format("成功检索到 %d 个活动告警", simplifiedAlerts.size()));
            
            String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            logger.info("Prometheus 告警查询完成: 找到 {} 个告警", simplifiedAlerts.size());
            
            return jsonResult;
            
        } catch (DependencyUnavailableException e) {
            logger.warn("Prometheus 告警查询被熔断, dependency: {}, operation: {}",
                    e.getDependency(), e.getOperation());
            return buildErrorResponse("Prometheus 熔断，证据缺失: 无法查询活动告警",
                    e.getMessage(), e.getErrorCode());
        } catch (Exception e) {
            logger.error("查询 Prometheus 告警失败", e);
            return buildErrorResponse("查询 Prometheus 失败，证据缺失", e.getMessage(), "DEPENDENCY_ERROR");
        }
    }

    private String doQueryMetricTrend(String metric, String service, String instance, String window, String step) {
        logger.info("开始查询指标趋势, metric: {}, service: {}, instance: {}, window: {}, step: {}, Mock模式: {}",
                metric, service, instance, window, step, mockEnabled);

        if (!MetricCatalog.isSupported(metric)) {
            return buildMetricTrendErrorResponse(metric, window, null,
                    "不支持的指标: " + metric + "。支持的指标: " + MetricCatalog.supportedNamesText(),
                    "不支持的指标: " + metric, "UNSUPPORTED_METRIC");
        }

        String query = buildMetricTrendQuery(metric, service, instance);
        try {
            List<MetricPoint> points = mockEnabled
                    ? buildMockTrendPoints(metric, window, step)
                    : fetchPrometheusTrend(query, window, step);
            MetricTrendSummary summary = summarizeTrend(metric, points);

            MetricTrendOutput output = new MetricTrendOutput();
            output.setSuccess(true);
            output.setDataMode(mockEnabled ? "MOCK" : "REAL");
            output.setMetric(metric);
            output.setWindow(window);
            output.setStep(step);
            output.setQuery(query);
            output.setPoints(points);
            output.setSummary(summary);
            output.setMessage(buildMetricTrendMessage(metric, window, summary, points));

            logger.info("指标趋势查询完成, metric: {}, points: {}, anomalous: {}",
                    metric, points.size(), summary.isAnomalous());
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (DependencyUnavailableException e) {
            logger.warn("Prometheus 指标趋势查询被熔断, metric: {}, dependency: {}, operation: {}",
                    metric, e.getDependency(), e.getOperation());
            return buildMetricTrendErrorResponse(metric, window, query,
                    "Prometheus 熔断，证据缺失: 无法查询指标趋势", e.getMessage(), e.getErrorCode());
        } catch (Exception e) {
            logger.error("查询指标趋势失败, metric: {}, query: {}", metric, query, e);
            return buildMetricTrendErrorResponse(metric, window, query, e.getMessage(), e.getMessage(), "DEPENDENCY_ERROR");
        }
    }
    
    /**
     * 构建 Mock 告警数据
     * 与 aiops-docs 文档中的告警类型对应：
     * - HighCPUUsage: CPU使用率过高
     * - HighMemoryUsage: 内存使用率过高
     * - HighDiskUsage: 磁盘使用率过高
     * - ServiceUnavailable: 服务不可用
     * - SlowResponse: 响应时间过长
     */
    private List<SimplifiedAlert> buildMockAlerts() {
        List<SimplifiedAlert> alerts = new ArrayList<>();
        Instant now = Instant.now();
        
        // 告警1: CPU使用率过高 - 持续约25分钟
        SimplifiedAlert cpuAlert = new SimplifiedAlert();
        cpuAlert.setAlertName("HighCPUUsage");
        cpuAlert.setDescription("服务 payment-service 的 CPU 使用率持续超过 80%，当前值为 92%。" +
                "实例: pod-payment-service-7d8f9c6b5-x2k4m，命名空间: production");
        cpuAlert.setState("firing");
        Instant cpuActiveAt = now.minus(25, ChronoUnit.MINUTES);
        cpuAlert.setActiveAt(cpuActiveAt.toString());
        cpuAlert.setDuration(calculateDuration(cpuActiveAt.toString()));
        alerts.add(cpuAlert);
        
        // 告警2: 内存使用率过高 - 持续约15分钟
        SimplifiedAlert memoryAlert = new SimplifiedAlert();
        memoryAlert.setAlertName("HighMemoryUsage");
        memoryAlert.setDescription("服务 order-service 的内存使用率持续超过 85%，当前值为 91%。" +
                "JVM堆内存使用: 3.8GB/4GB，可能存在内存泄漏风险。" +
                "实例: pod-order-service-5c7d8e9f1-m3n2p，命名空间: production");
        memoryAlert.setState("firing");
        Instant memoryActiveAt = now.minus(15, ChronoUnit.MINUTES);
        memoryAlert.setActiveAt(memoryActiveAt.toString());
        memoryAlert.setDuration(calculateDuration(memoryActiveAt.toString()));
        alerts.add(memoryAlert);
        
        // 告警3: 响应时间过长 - 持续约10分钟
        SimplifiedAlert slowAlert = new SimplifiedAlert();
        slowAlert.setAlertName("SlowResponse");
        slowAlert.setDescription("服务 user-service 的 P99 响应时间持续超过 3 秒，当前值为 4.2 秒。" +
                "受影响接口: /api/v1/users/profile, /api/v1/users/orders。" +
                "可能原因：数据库慢查询或下游服务延迟");
        slowAlert.setState("firing");
        Instant slowActiveAt = now.minus(10, ChronoUnit.MINUTES);
        slowAlert.setActiveAt(slowActiveAt.toString());
        slowAlert.setDuration(calculateDuration(slowActiveAt.toString()));
        alerts.add(slowAlert);
        
        return alerts;
    }
    
    /**
     * 从 Prometheus API 获取告警数据
     */
    private PrometheusAlertsResult fetchPrometheusAlerts() throws Exception {
        return DependencyGuardExecutor.executeChecked(
                dependencyGuard, "prometheus", TOOL_QUERY_PROMETHEUS_ALERTS, this::fetchPrometheusAlertsDirect);
    }

    private PrometheusAlertsResult fetchPrometheusAlertsDirect() throws Exception {
        String apiUrl = prometheusBaseUrl + "/api/v1/alerts";
        logger.debug("请求 Prometheus API: {}", apiUrl);
        
        Request request = new Request.Builder()
                .url(apiUrl)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute();
             ResponseBody body = response.body()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP 请求失败: " + response.code());
            }

            if (body == null) {
                throw new RuntimeException("Prometheus API 返回空响应体");
            }

            String responseBody = body.string();
            PrometheusAlertsResult result = objectMapper.readValue(responseBody, PrometheusAlertsResult.class);
            if (!"success".equals(result.getStatus())) {
                throw new RuntimeException("Prometheus API 返回非成功状态: "
                        + result.getStatus() + ", " + result.getError());
            }
            return result;
        }
    }

    private List<MetricPoint> fetchPrometheusTrend(String query, String window, String step) throws Exception {
        return DependencyGuardExecutor.executeChecked(dependencyGuard, "prometheus", TOOL_QUERY_METRIC_TREND,
                () -> fetchPrometheusTrendDirect(query, window, step));
    }

    private List<MetricPoint> fetchPrometheusTrendDirect(String query, String window, String step) throws Exception {
        if (httpClient == null) {
            throw new IllegalStateException("OkHttpClient 未初始化，无法查询 Prometheus");
        }
        String baseUrl = prometheusBaseUrl == null ? "" : prometheusBaseUrl.trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        HttpUrl parsedUrl = HttpUrl.parse(baseUrl + "/api/v1/query_range");
        if (parsedUrl == null) {
            throw new IllegalArgumentException("Prometheus base-url 无效: " + prometheusBaseUrl);
        }

        Instant end = Instant.now();
        Instant start = end.minus(windowDuration(window));
        HttpUrl url = parsedUrl.newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("start", String.valueOf(start.getEpochSecond()))
                .addQueryParameter("end", String.valueOf(end.getEpochSecond()))
                .addQueryParameter("step", step)
                .build();

        logger.debug("请求 Prometheus query_range: {}", url);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute();
             ResponseBody body = response.body()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP 请求失败: " + response.code());
            }
            if (body == null) {
                throw new RuntimeException("Prometheus query_range 返回空响应体");
            }
            return parsePrometheusTrendPoints(body.string());
        }
    }

    private List<MetricPoint> parsePrometheusTrendPoints(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        if (!"success".equals(root.path("status").asText())) {
            String error = root.path("error").asText("Prometheus query_range 返回非成功状态");
            throw new RuntimeException(error);
        }

        JsonNode result = root.path("data").path("result");
        if (!result.isArray() || result.isEmpty()) {
            return Collections.emptyList();
        }

        List<MetricPoint> points = new ArrayList<>();
        for (JsonNode series : result) {
            JsonNode values = series.path("values");
            if (!values.isArray()) {
                continue;
            }
            for (JsonNode value : values) {
                if (!value.isArray() || value.size() < 2) {
                    continue;
                }
                double timestamp = value.get(0).asDouble();
                double metricValue = parseDouble(value.get(1).asText(), Double.NaN);
                if (Double.isNaN(metricValue) || Double.isInfinite(metricValue)) {
                    continue;
                }
                points.add(new MetricPoint(Instant.ofEpochMilli((long) (timestamp * 1000)).toString(), round(metricValue)));
            }
        }
        return points;
    }

    private String buildMetricTrendQuery(String metric, String service, String instance) {
        String appSelector = buildSelector("job", service, "instance", instance, Collections.emptyMap());
        String appErrorSelector = buildSelector("job", service, "instance", instance, Map.of("status", "=~\"5..\""));
        String podSelector = buildSelector("pod", firstNonBlank(instance, service), null, null, Map.of("container", "!\"\""));

        return switch (metric) {
            case "cpu_usage" -> "100 * avg(rate(container_cpu_usage_seconds_total" + podSelector + "[5m]))";
            case "memory_usage" -> "100 * avg(container_memory_working_set_bytes" + podSelector + ") / "
                    + "clamp_min(avg(container_spec_memory_limit_bytes" + podSelector + "), 1)";
            case "error_rate" -> "sum(rate(http_requests_total" + appErrorSelector + "[5m])) / "
                    + "clamp_min(sum(rate(http_requests_total" + appSelector + "[5m])), 1) * 100";
            case "p99_latency" -> "histogram_quantile(0.99, sum(rate(http_request_duration_seconds_bucket"
                    + appSelector + "[5m])) by (le))";
            case "restart_count" -> "sum(increase(kube_pod_container_status_restarts_total"
                    + buildSelector("pod", firstNonBlank(instance, service), null, null, Collections.emptyMap()) + "[5m]))";
            case "jvm_gc_collection_seconds_count" -> "sum(rate(jvm_gc_collection_seconds_count"
                    + appSelector + "[5m]))";
            default -> metric;
        };
    }

    private String buildSelector(String primaryLabel,
                                 String primaryValue,
                                 String secondaryLabel,
                                 String secondaryValue,
                                 Map<String, String> extraMatchers) {
        List<String> matchers = new ArrayList<>();
        if (primaryLabel != null && primaryValue != null && !primaryValue.isBlank()) {
            matchers.add(primaryLabel + "=~\"" + regexContains(primaryValue) + "\"");
        }
        if (secondaryLabel != null && secondaryValue != null && !secondaryValue.isBlank()) {
            matchers.add(secondaryLabel + "=~\"" + regexContains(secondaryValue) + "\"");
        }
        for (Map.Entry<String, String> entry : extraMatchers.entrySet()) {
            matchers.add(entry.getKey() + entry.getValue());
        }
        return matchers.isEmpty() ? "{}" : "{" + String.join(",", matchers) + "}";
    }

    private List<MetricPoint> buildMockTrendPoints(String metric, String window, String step) {
        int count = mockPointCount(window, step);
        Instant start = Instant.now().minus(windowDuration(window));
        Duration interval = count <= 1 ? Duration.ZERO : windowDuration(window).dividedBy(count - 1);
        List<MetricPoint> points = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            double ratio = count <= 1 ? 1.0 : (double) i / (count - 1);
            double value = switch (metric) {
                case "cpu_usage" -> 58.0 + ratio * 36.0;
                case "memory_usage" -> 64.0 + ratio * 28.0;
                case "error_rate" -> ratio < 0.75 ? 0.2 + ratio * 0.8 : 2.0 + (ratio - 0.75) * 40.0;
                case "p99_latency" -> 0.8 + ratio * ratio * 3.6;
                case "restart_count" -> ratio < 0.55 ? 0.0 : Math.floor((ratio - 0.55) * 8.0);
                case "jvm_gc_collection_seconds_count" -> ratio < 0.65
                        ? 0.2 + ratio * 0.4
                        : 1.0 + (ratio - 0.65) * 8.0;
                default -> 0.0;
            };
            points.add(new MetricPoint(start.plus(interval.multipliedBy(i)).toString(), round(value)));
        }
        return points;
    }

    private MetricTrendSummary summarizeTrend(String metric, List<MetricPoint> points) {
        MetricTrendSummary summary = new MetricTrendSummary();
        if (points == null || points.isEmpty()) {
            summary.setMin(0);
            summary.setMax(0);
            summary.setAvg(0);
            summary.setLatest(0);
            summary.setDirection("stable");
            summary.setAnomalous(false);
            return summary;
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double total = 0;
        for (MetricPoint point : points) {
            double value = point.getValue();
            min = Math.min(min, value);
            max = Math.max(max, value);
            total += value;
        }

        double first = points.get(0).getValue();
        double latest = points.get(points.size() - 1).getValue();
        double avg = total / points.size();

        summary.setMin(round(min));
        summary.setMax(round(max));
        summary.setAvg(round(avg));
        summary.setLatest(round(latest));
        summary.setDirection(detectDirection(first, latest, max, avg));
        summary.setAnomalous(isAnomalous(metric, latest, max));
        return summary;
    }

    private String detectDirection(double first, double latest, double max, double avg) {
        double delta = latest - first;
        double base = Math.max(Math.abs(first), 1.0);
        if (max > Math.max(avg * 2.0, first + base * 0.8) && latest > avg * 1.3) {
            return "spiking";
        }
        if (Math.abs(delta) <= base * 0.08) {
            return "stable";
        }
        return delta > 0 ? "increasing" : "decreasing";
    }

    private boolean isAnomalous(String metric, double latest, double max) {
        return switch (metric) {
            case "cpu_usage" -> latest >= 80 || max >= 90;
            case "memory_usage" -> latest >= 85 || max >= 90;
            case "error_rate" -> latest >= 1 || max >= 5;
            case "p99_latency" -> latest >= 3 || max >= 3;
            case "restart_count" -> latest > 0 || max > 0;
            case "jvm_gc_collection_seconds_count" -> latest >= 1 || max >= 1;
            default -> false;
        };
    }

    private String buildMetricTrendMessage(String metric, String window, MetricTrendSummary summary, List<MetricPoint> points) {
        if (points == null || points.isEmpty()) {
            return metric + " 最近 " + window + " 未查询到趋势点";
        }
        return String.format(Locale.ROOT,
                "%s 最近 %s %s，latest=%.2f，max=%.2f，avg=%.2f，anomalous=%s",
                metric, window, directionText(summary.getDirection()), summary.getLatest(), summary.getMax(),
                summary.getAvg(), summary.isAnomalous());
    }

    private String directionText(String direction) {
        return switch (direction) {
            case "increasing" -> "持续上升";
            case "decreasing" -> "持续下降";
            case "spiking" -> "出现突增";
            default -> "整体平稳";
        };
    }
    
    /**
     * 计算从 activeAt 到现在的持续时间
     */
    private String calculateDuration(String activeAtStr) {
        try {
            Instant activeAt = Instant.parse(activeAtStr);
            Duration duration = Duration.between(activeAt, Instant.now());
            
            long hours = duration.toHours();
            long minutes = duration.toMinutes() % 60;
            long seconds = duration.getSeconds() % 60;
            
            if (hours > 0) {
                return String.format("%dh%dm%ds", hours, minutes, seconds);
            } else if (minutes > 0) {
                return String.format("%dm%ds", minutes, seconds);
            } else {
                return String.format("%ds", seconds);
            }
        } catch (Exception e) {
            logger.warn("解析时间失败: {}", activeAtStr, e);
            return "unknown";
        }
    }

    private String normalizeMetric(String metric) {
        return MetricCatalog.normalize(metric);
    }

    private String normalizeWindow(String window) {
        String normalized = window == null ? "" : window.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_TREND_WINDOWS.contains(normalized) ? normalized : "1h";
    }

    private String normalizeStep(String window, String step) {
        String normalized = step == null ? "" : step.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("\\d+[smh]")) {
            return normalized;
        }
        return switch (window) {
            case "15m" -> "30s";
            case "6h" -> "5m";
            default -> "1m";
        };
    }

    private Duration windowDuration(String window) {
        return switch (normalizeWindow(window)) {
            case "15m" -> Duration.ofMinutes(15);
            case "6h" -> Duration.ofHours(6);
            default -> Duration.ofHours(1);
        };
    }

    private int mockPointCount(String window, String step) {
        long windowSeconds = windowDuration(window).toSeconds();
        long stepSeconds = parseStepSeconds(step);
        long count = stepSeconds <= 0 ? 20 : windowSeconds / stepSeconds + 1;
        return (int) Math.max(8, Math.min(count, 80));
    }

    private long parseStepSeconds(String step) {
        if (step == null || !step.matches("\\d+[smh]")) {
            return 60;
        }
        long value = Long.parseLong(step.substring(0, step.length() - 1));
        char unit = step.charAt(step.length() - 1);
        return switch (unit) {
            case 's' -> value;
            case 'h' -> value * 3600;
            default -> value * 60;
        };
    }

    private String buildTrendQueryParams(String metric, String service, String instance, String window, String step) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("metric", metric);
            params.put("service", service);
            params.put("instance", instance);
            params.put("window", window);
            params.put("step", step);
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildMetricTrendErrorResponse(String metric, String window, String query,
                                                String message, String error, String errorCode) {
        try {
            MetricTrendOutput output = new MetricTrendOutput();
            output.setSuccess(false);
            output.setDataMode(mockEnabled ? "MOCK" : "REAL");
            output.setMetric(metric);
            output.setWindow(window);
            output.setQuery(query);
            output.setPoints(Collections.emptyList());
            output.setSummary(summarizeTrend(metric, Collections.emptyList()));
            output.setMessage(message == null || message.isBlank() ? "查询指标趋势失败" : message);
            output.setError(error);
            output.setErrorCode(errorCode);
            output.setNextAction("UNSUPPORTED_METRIC".equals(errorCode)
                    ? "STOP_TOOL_DIRECTION" : "REPORT_MISSING_EVIDENCE");
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"metric\":\"%s\",\"window\":\"%s\",\"message\":\"%s\",\"error\":\"%s\",\"errorCode\":\"%s\"}",
                    metric, window, message, error, errorCode);
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private String regexContains(String value) {
        return ".*" + value.trim()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace(".", "\\.")
                .replace("*", "\\*")
                .replace("+", "\\+")
                .replace("?", "\\?")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("|", "\\|")
                .replace("^", "\\^")
                .replace("$", "\\$")
                + ".*";
    }

    private double parseDouble(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
    
    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String message, String error, String errorCode) {
        try {
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            output.setError(error);
            output.setErrorCode(errorCode);
            output.setDataMode(mockEnabled ? "MOCK" : "REAL");
            output.setNextAction("DEPENDENCY_ERROR".equals(errorCode)
                    ? "REPORT_MISSING_EVIDENCE" : "STOP_TOOL_DIRECTION");
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\",\"error\":\"%s\",\"errorCode\":\"%s\"}",
                    message, error, errorCode);
        }
    }

    // ==================== 数据模型 ====================
    
    /**
     * Prometheus 告警信息结构
     */
    @Data
    public static class PrometheusAlert {
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String state;
        private String activeAt;
        private String value;
    }
    
    /**
     * Prometheus 告警查询结果
     */
    @Data
    public static class PrometheusAlertsResult {
        private String status;
        private AlertsData data;
        private String error;
        private String errorType;
    }
    
    @Data
    public static class AlertsData {
        private List<PrometheusAlert> alerts = new ArrayList<>();
    }
    
    /**
     * 简化的告警信息
     */
    @Data
    public static class SimplifiedAlert {
        @JsonProperty("alert_name")
        private String alertName;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("state")
        private String state;
        
        @JsonProperty("active_at")
        private String activeAt;
        
        @JsonProperty("duration")
        private String duration;
    }
    
    /**
     * 告警查询输出
     */
    @Data
    public static class PrometheusAlertsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("alerts")
        private List<SimplifiedAlert> alerts;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("error")
        private String error;

        @JsonProperty("errorCode")
        private String errorCode;

        @JsonProperty("dataMode")
        private String dataMode;

        @JsonProperty("nextAction")
        private String nextAction;

    }

    @Data
    public static class MetricTrendOutput {
        @JsonProperty("success")
        private boolean success;

        @JsonProperty("metric")
        private String metric;

        @JsonProperty("window")
        private String window;

        @JsonProperty("step")
        private String step;

        @JsonProperty("query")
        private String query;

        @JsonProperty("points")
        private List<MetricPoint> points = new ArrayList<>();

        @JsonProperty("summary")
        private MetricTrendSummary summary;

        @JsonProperty("message")
        private String message;

        @JsonProperty("error")
        private String error;

        @JsonProperty("errorCode")
        private String errorCode;

        @JsonProperty("dataMode")
        private String dataMode;

        @JsonProperty("nextAction")
        private String nextAction;
    }

    @Data
    public static class MetricPoint {
        @JsonProperty("timestamp")
        private String timestamp;

        @JsonProperty("value")
        private double value;

        public MetricPoint() {
        }

        public MetricPoint(String timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }

    @Data
    public static class MetricTrendSummary {
        @JsonProperty("min")
        private double min;

        @JsonProperty("max")
        private double max;

        @JsonProperty("avg")
        private double avg;

        @JsonProperty("latest")
        private double latest;

        @JsonProperty("direction")
        private String direction;

        @JsonProperty("anomalous")
        private boolean anomalous;
    }
}
