package org.example.config;

import org.example.service.TenantContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdcContextTest {

    @Test
    void wrapCallable_shouldPropagateAndRestoreMdcAcrossExecutorBoundary() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MDC.put("trace_id", "trace-test");
            try (TenantContext.Scope ignored = TenantContext.open("tenant-a")) {
                Future<String> value = executor.submit(MdcContext.wrapCallable(
                        () -> MDC.get("trace_id") + ":" + TenantContext.currentTenant()));
                assertEquals("trace-test:tenant-a", value.get());
                assertEquals("tenant-a", TenantContext.currentTenant());
            }
            assertEquals("trace-test", MDC.get("trace_id"));
            assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantContext.currentTenant());
        } finally {
            MDC.clear();
            executor.shutdownNow();
        }
    }

    @Test
    void withTraceparent_shouldPreserveTraceIdAndCreateAWorkerSpan() {
        MDC.put("request_id", "request-test");
        try {
            MdcContext.withTraceparent(
                    "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                    () -> {
                        assertEquals("0123456789abcdef0123456789abcdef", MDC.get("trace_id"));
                        assertTrue(MDC.get("span_id").matches("[0-9a-f]{16}"));
                        assertEquals("request-test", MDC.get("request_id"));
                    });
            assertEquals("request-test", MDC.get("request_id"));
            assertEquals(null, MDC.get("trace_id"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void extractTraceparent_shouldIgnoreMalformedPayload() {
        assertEquals("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                MdcContext.extractTraceparent(
                        "{\"traceparent\":\"00-0123456789ABCDEF0123456789ABCDEF-0123456789ABCDEF-01\"}"));
        assertEquals(null, MdcContext.extractTraceparent("{\"traceparent\":\"invalid\"}"));
    }
}
