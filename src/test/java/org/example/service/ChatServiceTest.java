package org.example.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceTest {

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "resourceLoader", new DefaultResourceLoader());
    }

    @Test
    void buildSystemPrompt_shouldIncludePrivateMemoriesWhenProvided() {
        VectorSearchService.SearchResult memory = new VectorSearchService.SearchResult();
        memory.setContent("[用户私人记忆] 用户偏好使用中文回答");

        String prompt = chatService.buildSystemPrompt(
                List.of(Map.of("role", "user", "content", "之前的问题")),
                List.of(memory)
        );

        assertTrue(prompt.contains("--- 私人记忆 ---"));
        assertTrue(prompt.contains("用户偏好使用中文回答"));
        assertTrue(prompt.contains("--- 对话历史 ---"));
    }
}
