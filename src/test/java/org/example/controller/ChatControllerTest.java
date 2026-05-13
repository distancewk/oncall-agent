package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppMemoryProperties;
import org.example.dto.ApiResponse;
import org.example.dto.ChatSessionRecord;
import org.example.dto.ChatSessionSummary;
import org.example.service.AiOpsService;
import org.example.service.AlertService;
import org.example.service.ChatService;
import org.example.service.SessionManager;
import org.example.service.VectorSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiOpsService aiOpsService;

    @MockBean(name = "dashScopeChatModel")
    private DashScopeChatModel dashScopeChatModel;

    @MockBean(name = "dashScopeChatModelAiOps")
    private DashScopeChatModel dashScopeChatModelAiOps;

    @MockBean
    private ChatService chatService;

    @MockBean
    private ToolCallbackProvider tools;

    @MockBean(name = "chatTaskExecutor")
    private Executor executor;

    @MockBean
    private SessionManager sessionManager;

    @MockBean
    private AlertService alertService;

    @MockBean
    private VectorSearchService vectorSearchService;

    @MockBean
    private AppMemoryProperties appMemoryProperties;

    @Test
    void chat_shouldReturnError_whenQuestionIsEmpty() throws Exception {
        String requestJson = """
            {"Id": "test-session", "Question": ""}
            """;

        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.errorMessage").value("问题内容不能为空"));
    }

    @Test
    void chat_shouldReturnError_whenQuestionIsNull() throws Exception {
        String requestJson = """
            {"Id": "test-session", "Question": null}
            """;

        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.errorMessage").value("问题内容不能为空"));
    }

    @Test
    void clearChatHistory_shouldReturnError_whenSessionIdIsEmpty() throws Exception {
        String requestJson = """
            {"Id": ""}
            """;

        mockMvc.perform(post("/api/chat/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("会话ID不能为空"));
    }

    @Test
    void clearChatHistory_shouldReturnError_whenSessionDoesNotExist() throws Exception {
        when(sessionManager.getSession("non-existent")).thenReturn(null);

        String requestJson = """
            {"Id": "non-existent"}
            """;

        mockMvc.perform(post("/api/chat/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @Test
    void getSessionInfo_shouldReturnError_whenSessionDoesNotExist() throws Exception {
        when(sessionManager.getSession("bad-id")).thenReturn(null);

        mockMvc.perform(get("/api/chat/session/bad-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @Test
    void listChatSessions_shouldReturnPersistedSessionSummaries() throws Exception {
        ChatSessionSummary summary = new ChatSessionSummary();
        summary.setSessionId("session-1");
        summary.setTitle("历史问题");
        summary.setMessagePairCount(2);
        summary.setCreateTime(100L);
        summary.setUpdateTime(200L);
        when(sessionManager.listSessions()).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sessionId").value("session-1"))
                .andExpect(jsonPath("$.data[0].title").value("历史问题"))
                .andExpect(jsonPath("$.data[0].messagePairCount").value(2));
    }

    @Test
    void getSessionMessages_shouldReturnPersistedFullMessageHistory() throws Exception {
        ChatSessionRecord record = new ChatSessionRecord();
        record.setSessionId("session-1");
        record.setCreateTime(100L);
        record.setUpdateTime(200L);
        record.setMessageHistory(new ArrayList<>(List.of(
                Map.of("role", "user", "content", "hello"),
                Map.of("role", "assistant", "content", "hi")
        )));
        when(sessionManager.getSessionMessages("session-1")).thenReturn(java.util.Optional.of(record));

        mockMvc.perform(get("/api/chat/session/session-1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value("session-1"))
                .andExpect(jsonPath("$.data.messageHistory[0].role").value("user"))
                .andExpect(jsonPath("$.data.messageHistory[0].content").value("hello"));
    }

    @Test
    void deleteChatSession_shouldRemovePersistedSession() throws Exception {
        when(sessionManager.deleteSession("session-1")).thenReturn(true);

        mockMvc.perform(delete("/api/chat/session/session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("会话已删除"));

        verify(sessionManager).deleteSession("session-1");
    }

    @Test
    void chat_shouldRecallPrivateMemoriesAndInjectThemIntoPrompt() throws Exception {
        SessionManager.SessionInfo session = new SessionManager.SessionInfo("session-1");
        when(sessionManager.getOrCreateSession("session-1")).thenReturn(session);
        when(appMemoryProperties.isPrivateRecallEnabled()).thenReturn(true);
        when(appMemoryProperties.getPrivateRecallTopK()).thenReturn(3);

        VectorSearchService.SearchResult memory = new VectorSearchService.SearchResult();
        memory.setContent("[用户私人记忆] 用户偏好中文回答");
        when(vectorSearchService.searchSessionMemories("继续上次的话题", "session-1", 3))
                .thenReturn(List.of(memory));
        when(chatService.buildSystemPrompt(eq(List.of()), eq(List.of(memory)))).thenReturn("prompt-with-memory");
        when(chatService.executeChat(any(), eq("继续上次的话题"))).thenReturn("好的");

        String requestJson = """
            {"Id": "session-1", "Question": "继续上次的话题"}
            """;

        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("好的"));

        verify(vectorSearchService).searchSessionMemories("继续上次的话题", "session-1", 3);
        verify(chatService).buildSystemPrompt(eq(List.of()), eq(List.of(memory)));
    }
}
