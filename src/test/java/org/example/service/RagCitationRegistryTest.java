package org.example.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagCitationRegistryTest {

    @AfterEach
    void clear() {
        RagCitationRegistry.clearCurrent();
        MDC.clear();
    }

    @Test
    void recordIds_shouldBeConsumedOnceForCurrentTrace() {
        MDC.put("trace_id", "trace-rag-test");
        RagCitationRegistry.recordIds(List.of("doc-1", "doc-2"));

        assertEquals(java.util.Set.of("doc-1", "doc-2"), RagCitationRegistry.consumeCurrent());
        assertTrue(RagCitationRegistry.consumeCurrent().isEmpty());
    }

    @Test
    void recordIds_shouldKeepTheRegistryBoundedAcrossMultipleCalls() {
        MDC.put("trace_id", "trace-rag-bound");
        List<String> firstBatch = new ArrayList<>();
        List<String> secondBatch = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            firstBatch.add("first-" + index);
            secondBatch.add("second-" + index);
        }

        RagCitationRegistry.recordIds(firstBatch);
        RagCitationRegistry.recordIds(secondBatch);

        assertEquals(64, RagCitationRegistry.consumeCurrent().size());
    }
}
