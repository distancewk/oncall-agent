package org.example.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextTest {

    @Test
    void from_shouldContinueValidTraceAndCreateNewSpan() {
        TraceContext.Context context = TraceContext.from(
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");

        assertEquals("0123456789abcdef0123456789abcdef", context.traceId());
        assertNotEquals("0123456789abcdef", context.spanId());
        assertTrue(context.traceparent().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"));
    }

    @Test
    void from_shouldRejectMalformedOrZeroTraceParent() {
        TraceContext.Context malformed = TraceContext.from("not-a-trace");
        TraceContext.Context zero = TraceContext.from(
                "00-00000000000000000000000000000000-0123456789abcdef-01");

        assertTrue(malformed.traceparent().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"));
        assertTrue(zero.traceparent().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"));
        assertNotEquals("00000000000000000000000000000000", zero.traceId());
    }
}
