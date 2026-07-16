package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRelevanceGateTest {

    private final MemoryRelevanceGate gate = new MemoryRelevanceGate();

    @Test
    void shouldRecallExplicitMemoryReferences() {
        assertTrue(gate.shouldRecall("继续上次的话题，按照我的偏好给方案"));
        assertTrue(gate.shouldRecall("Please remember my preference for concise answers"));
    }

    @Test
    void shouldSkipOrdinaryQuestions() {
        assertFalse(gate.shouldRecall("请解释什么是幂等性"));
        assertFalse(gate.shouldRecall("帮我把这段话改写得更简洁"));
        assertFalse(gate.shouldRecall(""));
    }
}
