package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

class RagGroundingPolicyTest {

    private final RagGroundingPolicy policy = new RagGroundingPolicy();

    @Test
    void shouldRequireCitationForInternalKnowledgeQuestions() {
        assertTrue(policy.requiresCitation("请查询内部文档中的发布流程"));
        assertTrue(policy.requiresCitation("What does the runbook say?"));
        assertFalse(policy.requiresCitation("帮我写一个 Java 方法"));
        assertFalse(policy.requiresCitation("帮我写一份技术文档"));
    }

    @Test
    void shouldAcceptExactSourceCitation() {
        String answer = "按文档执行灰度发布。[来源: doc-123]";

        assertTrue(policy.hasCitation(answer));
        assertEquals(answer, policy.enforce("内部发布流程是什么？", answer));
    }

    @Test
    void shouldRejectCitationPlaceholder() {
        String answer = "按文档执行灰度发布。[来源: <id>]";

        assertFalse(policy.hasCitation(answer));
        assertEquals(policy.fallbackMessage(),
                policy.enforce("内部发布流程是什么？", answer));
    }

    @Test
    void shouldRejectUncitedInternalFact() {
        assertEquals(policy.fallbackMessage(),
                policy.enforce("知识库里的发布流程是什么？", "直接执行发布即可。"));
    }

    @Test
    void shouldAllowExplicitNoEvidenceAnswer() {
        String answer = "知识库没有找到与该问题相关的依据。";

        assertTrue(policy.explicitlyReportsNoEvidence(answer));
        assertEquals(answer, policy.enforce("请查内部文档", answer));
    }

    @Test
    void shouldRejectCitationOutsideKnownRetrievalResults() {
        String forged = "按文档执行灰度发布。[来源: forged-id]";
        String known = "按文档执行灰度发布。[来源: doc-123]";

        assertEquals(policy.fallbackMessage(), policy.enforce(
                "内部发布流程是什么？", forged, Set.of("doc-123")));
        assertEquals(known, policy.enforce(
                "内部发布流程是什么？", known, Set.of("doc-123")));
    }
}
