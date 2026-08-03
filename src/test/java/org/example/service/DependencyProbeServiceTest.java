package org.example.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CheckHealthResponse;
import io.milvus.grpc.GetVersionResponse;
import io.milvus.param.R;
import okhttp3.OkHttpClient;
import org.example.config.AppDependencyProbeProperties;
import org.example.config.AppResilienceProperties;
import org.example.dto.DependencyProbeSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DependencyProbeServiceTest {

    @Test
    void prometheusPayloadIsHealthy_shouldRequireSuccessAndVersion() {
        assertTrue(DependencyProbeService.prometheusPayloadIsHealthy(
                "{\"status\":\"success\",\"data\":{\"version\":\"2.50.0\"}}"));
        assertFalse(DependencyProbeService.prometheusPayloadIsHealthy("<html>login</html>"));
        assertFalse(DependencyProbeService.prometheusPayloadIsHealthy(
                "{\"status\":\"error\",\"data\":{}}"));
    }

    @Test
    void dashscopeDeployableModelsPayloadIsHealthy_shouldRequireOutputModels() {
        assertTrue(DependencyProbeService.dashscopeDeployableModelsPayloadIsHealthy(
                "{\"output\":{\"models\":[]},\"request_id\":\"req-1\"}"));
        assertFalse(DependencyProbeService.dashscopeDeployableModelsPayloadIsHealthy(
                "{\"output\":{\"message\":\"ok\"}}"));
        assertFalse(DependencyProbeService.dashscopeDeployableModelsPayloadIsHealthy(
                "{\"output\":{},\"models\":[]}"));
    }

    @Test
    void clsPayloadIsHealthy_shouldRequireNativeResponseShape() {
        assertTrue(DependencyProbeService.clsPayloadIsHealthy(
                "{\"Response\":{\"RequestId\":\"req-1\",\"Results\":[]}}"));
        assertTrue(DependencyProbeService.clsPayloadIsHealthy(
                "{\"Response\":{\"RequestId\":\"req-2\",\"Results\":null}}"));
        assertFalse(DependencyProbeService.clsPayloadIsHealthy("{\"success\":true}"));
        assertFalse(DependencyProbeService.clsPayloadIsHealthy("not-json"));
    }

    @Test
    void httpStateForCode_shouldSeparateAuthenticationFailures() {
        assertEquals("UP", DependencyProbeService.httpStateForCode(200));
        assertEquals("AUTH_FAILED", DependencyProbeService.httpStateForCode(401));
        assertEquals("AUTH_FAILED", DependencyProbeService.httpStateForCode(403));
        assertEquals("DEGRADED", DependencyProbeService.httpStateForCode(404));
        assertEquals("DOWN", DependencyProbeService.httpStateForCode(500));
    }

    @Test
    void probeAll_shouldReturnExplicitDisabledStateWithoutNetworkCalls() {
        AppDependencyProbeProperties properties = new AppDependencyProbeProperties();
        properties.setEnabled(false);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        List<DependencyProbeSnapshot> snapshots = new DependencyProbeService(
                new OkHttpClient(), properties, new ObservabilityMetrics(registry)).probeAll();

        assertEquals(5, snapshots.size());
        assertEquals(
                Map.of("prometheus", "DISABLED", "cls-logs", "DISABLED",
                        "dashscope-chat", "DISABLED", "milvus", "DISABLED", "mcp", "DISABLED"),
                statesByName(snapshots));
        assertEquals(1.0, registry.get("superbizagent.dependencies.probes")
                .tag("dependency", "prometheus")
                .tag("outcome", "DISABLED")
                .counter().count());
    }

    @Test
    void probeAll_shouldMarkUnconfiguredEndpointsAndInvalidMilvusWithoutNetworkCalls() {
        AppDependencyProbeProperties properties = new AppDependencyProbeProperties();
        properties.setPrometheusUrl("");
        properties.setClsUrl("");
        properties.setDashscopeUrl("");
        properties.setMilvusHost("");
        properties.setMilvusPort(0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        List<DependencyProbeSnapshot> snapshots = new DependencyProbeService(
                new OkHttpClient(), properties, new ObservabilityMetrics(registry)).probeAll();

        assertEquals(
                Map.of("prometheus", "NOT_CONFIGURED", "cls-logs", "NOT_CONFIGURED",
                        "dashscope-chat", "NOT_CONFIGURED", "milvus", "NOT_CONFIGURED",
                        "mcp", "NOT_CONFIGURED"),
                statesByName(snapshots));
    }

    @Test
    void nativeClsProbe_shouldFailClosedWhenReadOnlyTopicIsMissing() {
        AppDependencyProbeProperties properties = new AppDependencyProbeProperties();
        properties.setClsNativeSigningEnabled(true);
        properties.setClsUrl("https://cls.example.test");
        properties.setClsSecretId("id");
        properties.setClsSecretKey("key");
        properties.setPrometheusUrl("");
        properties.setDashscopeUrl("");
        properties.setMilvusHost("");
        properties.setMilvusPort(0);

        List<DependencyProbeSnapshot> snapshots = new DependencyProbeService(
                new OkHttpClient(), properties, new ObservabilityMetrics(new SimpleMeterRegistry()))
                .probeAll();

        assertEquals("NOT_CONFIGURED", snapshots.stream()
                .filter(snapshot -> "cls-logs".equals(snapshot.getName()))
                .findFirst().orElseThrow().getState());
    }

    @Test
    void nativeClsProbe_shouldUseDependencyGuardForTransientFailures() throws Exception {
        AppDependencyProbeProperties properties = new AppDependencyProbeProperties();
        properties.setClsNativeSigningEnabled(true);
        properties.setClsUrl("https://cls.example.test");
        properties.setClsProbeTopicId("topic-1");
        properties.setClsSecretId("id");
        properties.setClsSecretKey("key");
        properties.setPrometheusUrl("");
        properties.setDashscopeUrl("");
        properties.setMilvusHost("");
        properties.setMilvusPort(0);

        ClsClient clsClient = mock(ClsClient.class);
        when(clsClient.execute(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new java.io.IOException("temporary CLS failure"));
        AppResilienceProperties resilience = new AppResilienceProperties();
        resilience.getDefaultConfig().setRetryMaxAttempts(2);
        DependencyProbeService service = new DependencyProbeService(
                new OkHttpClient(), properties, new ObservabilityMetrics(new SimpleMeterRegistry()), clsClient);
        ReflectionTestUtils.setField(service, "dependencyGuard", new DependencyGuard(resilience));

        assertEquals("DOWN", service.probeAll().stream()
                .filter(snapshot -> "cls-logs".equals(snapshot.getName()))
                .findFirst().orElseThrow().getState());
        verify(clsClient, times(2)).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void probeAll_shouldUseMilvusHealthAndVersionSemanticsWhenClientIsAvailable() {
        AppDependencyProbeProperties properties = new AppDependencyProbeProperties();
        properties.setPrometheusUrl("");
        properties.setClsUrl("");
        properties.setDashscopeUrl("");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.checkHealth()).thenReturn(R.success(
                CheckHealthResponse.newBuilder().setIsHealthy(true).build()));
        when(client.getVersion()).thenReturn(R.success(
                GetVersionResponse.newBuilder().setVersion("2.4.0").build()));

        DependencyProbeService service = new DependencyProbeService(
                new OkHttpClient(), properties, new ObservabilityMetrics(registry));
        ReflectionTestUtils.setField(service, "milvusClient", client);

        List<DependencyProbeSnapshot> snapshots = service.probeAll();

        assertEquals("UP", snapshots.stream()
                .filter(snapshot -> "milvus".equals(snapshot.getName()))
                .findFirst().orElseThrow().getState());
        assertEquals(1.0, registry.get("superbizagent.dependencies.probes")
                .tag("dependency", "milvus")
                .tag("outcome", "UP")
                .counter().count());
    }

    private Map<String, String> statesByName(List<DependencyProbeSnapshot> snapshots) {
        return snapshots.stream().collect(Collectors.toMap(
                DependencyProbeSnapshot::getName,
                DependencyProbeSnapshot::getState,
                (left, right) -> right,
                java.util.LinkedHashMap::new));
    }
}
