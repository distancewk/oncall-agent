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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExtractionServiceTest {

    @Test
    void extractAndStore_shouldDelegateEachExtractedFactToMemoryLifecycleService() {
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

        verify(memoryLifecycleService).storeMemory("session-1", "用户偏好 Java 方案");
        verify(memoryLifecycleService).storeMemory("session-1", "用户使用 Milvus");
    }
}
