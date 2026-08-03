package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagQueryRewritePolicyTest {

    private final RagQueryRewritePolicy policy = new RagQueryRewritePolicy();

    @Test
    void rewrite_shouldPreserveQuestionAndAddOperationalAliases() {
        String rewritten = policy.rewrite("CPU 使用率告警");

        assertTrue(rewritten.startsWith("CPU 使用率告警"));
        assertTrue(rewritten.contains("cpu_usage cpu utilization"));
    }

    @Test
    void rewrite_shouldNotChangeAlreadySpecificQuery() {
        assertEquals("cpu_usage p99", policy.rewrite("cpu_usage p99"));
    }

    @Test
    void rewrite_shouldHandleBlankInputWithoutInventingQuery() {
        assertEquals("", policy.rewrite("  "));
    }
}
