package org.example.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceExportServiceTest {

    @Test
    void buildSpan_shouldContainOnlyLowRiskHttpMetadata() {
        AppTraceExportProperties properties = new AppTraceExportProperties();
        properties.setServiceName("super biz/agent");
        TraceExportService service = new TraceExportService(
                new okhttp3.OkHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(), properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/incidents/incident-secret");
        request.setAttribute(TraceExportService.TRACE_ID_ATTRIBUTE,
                "0123456789abcdef0123456789abcdef");
        request.setAttribute(TraceExportService.SPAN_ID_ATTRIBUTE, "0123456789abcdef");
        request.setAttribute(TraceExportService.START_NANOS_ATTRIBUTE,
                System.nanoTime() - 1_000_000L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(503);

        Map<String, Object> span = service.buildSpan(request, response,
                new IllegalStateException("secret details must not be exported"));

        assertEquals("0123456789abcdef0123456789abcdef", span.get("traceId"));
        assertEquals("0123456789abcdef", span.get("id"));
        assertEquals("http.request", span.get("name"));
        assertTrue(((Long) span.get("duration")) > 0L);
        assertFalse(span.containsKey("request"));
        assertFalse(span.toString().contains("secret details"));
        assertEquals(Map.of("serviceName", "super_biz_agent"), span.get("localEndpoint"));
        Map<?, ?> tags = (Map<?, ?>) span.get("tags");
        assertEquals("POST", tags.get("http.method"));
        assertEquals("503", tags.get("http.status_code"));
        assertEquals("IllegalStateException", tags.get("error.type"));
    }

    @Test
    void export_shouldRemainDisabledWithoutEndpoint() {
        AppTraceExportProperties properties = new AppTraceExportProperties();
        properties.setEnabled(true);
        properties.setEndpoint(" ");
        TraceExportService service = new TraceExportService(
                new okhttp3.OkHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(), properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.setAttribute(TraceExportService.TRACE_ID_ATTRIBUTE, "trace-id");
        request.setAttribute(TraceExportService.SPAN_ID_ATTRIBUTE, "span-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.export(request, response, null);
    }
}
