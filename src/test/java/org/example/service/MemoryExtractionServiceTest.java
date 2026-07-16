package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExtractionServiceTest {

    @Test
    void extractAndStore_shouldBatchExtractedFacts() {
        DashScopeChatModel chatModel = mock(DashScopeChatModel.class);
        MemoryLifecycleService memoryLifecycleService = mock(MemoryLifecycleService.class);
        MemoryExtractionService service = new MemoryExtractionService();
        ReflectionTestUtils.setField(service, "dashScopeChatModel", chatModel);
        ReflectionTestUtils.setField(service, "memoryLifecycleService", memoryLifecycleService);

        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("用户偏好 Java 方案; 用户使用 Milvus"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        service.extractAndStore("session-1", List.of(
                Map.of("role", "user", "content", "我偏好 Java 方案"),
                Map.of("role", "assistant", "content", "已记录")));

        var captor = forClass(List.class);
        verify(memoryLifecycleService).storeMemories(eq("session-1"), captor.capture());
        @SuppressWarnings("unchecked")
        List<org.example.dto.MemoryFact> facts = (List<org.example.dto.MemoryFact>) captor.getValue();
        assertEquals(List.of("用户偏好 Java 方案", "用户使用 Milvus"),
                facts.stream().map(org.example.dto.MemoryFact::content).toList());
    }

    @Test
    void extractAndStore_shouldParseStructuredFactsAndDeduplicate() {
        DashScopeChatModel chatModel = mock(DashScopeChatModel.class);
        MemoryLifecycleService memoryLifecycleService = mock(MemoryLifecycleService.class);
        MemoryExtractionService service = new MemoryExtractionService();
        ReflectionTestUtils.setField(service, "dashScopeChatModel", chatModel);
        ReflectionTestUtils.setField(service, "memoryLifecycleService", memoryLifecycleService);

        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"memories\":["
                        + "{\"content\":\" 用户偏好中文 \" ,\"type\":\"PROFILE\",\"confidence\":1.4,\"importance\":-1},"
                        + "{\"content\":\"用户偏好中文\",\"type\":\"PROFILE\",\"confidence\":0.8,\"importance\":0.8}]}"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        service.extractAndStore("session-1", List.of(Map.of("role", "user", "content", "我偏好中文")));

        var captor = forClass(List.class);
        verify(memoryLifecycleService).storeMemories(eq("session-1"), captor.capture());
        @SuppressWarnings("unchecked")
        List<org.example.dto.MemoryFact> facts = (List<org.example.dto.MemoryFact>) captor.getValue();
        assertEquals(1, facts.size());
        assertEquals("PROFILE", facts.get(0).type());
        assertEquals(1.0, facts.get(0).confidence());
        assertEquals(0.0, facts.get(0).importance());
    }
}
