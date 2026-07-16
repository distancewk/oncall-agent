package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppMemoryProperties;
import org.example.config.AppChatProperties;
import org.example.dto.ApiResponse;
import org.example.dto.ChatSessionRecord;
import org.example.dto.ChatSessionSummary;
import org.example.service.AiOpsService;
import org.example.service.AlertService;
import org.example.service.ChatService;
import org.example.service.MemoryRelevanceGate;
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
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @MockBean
    private AppChatProperties appChatProperties;

    @MockBean
    private MemoryRelevanceGate memoryRelevanceGate;

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
        when(sessionManager.deleteSessionWithStatus("session-1"))
                .thenReturn(new SessionManager.SessionDeletionResult(true, true));

        mockMvc.perform(delete("/api/chat/session/session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("会话已删除"));

        verify(sessionManager).deleteSessionWithStatus("session-1");
    }

    @Test
    void deleteChatSession_shouldSurfacePrivateMemoryDeletionFailure() throws Exception {
        when(sessionManager.deleteSessionWithStatus("session-1"))
                .thenReturn(new SessionManager.SessionDeletionResult(true, false));

        mockMvc.perform(delete("/api/chat/session/session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("会话已删除，但私人记忆删除失败，请稍后重试"));
    }

    @Test
    void aiOpsEndpoint_shouldSendDemoModeNoticeBeforeRunningUnboundAnalysis() throws Exception {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(aiOpsService.executeAiOpsAnalysis(any(), any(), any())).thenReturn(Optional.empty());

        var result = mockMvc.perform(post("/api/ai_ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "当前接口为演示入口，不会持久化 DiagnosisRun 证据链")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(response.indexOf("当前接口为演示入口，不会持久化 DiagnosisRun 证据链")
                < response.indexOf("正在读取告警并拆解任务"));

        verify(aiOpsService).executeAiOpsAnalysis(any(), any(), any());
    }

    @Test
    void chat_shouldRecallPrivateMemoriesAndInjectThemIntoPrompt() throws Exception {
        SessionManager.SessionInfo session = new SessionManager.SessionInfo("session-1");
        when(sessionManager.getOrCreateSession("session-1")).thenReturn(session);
        when(appMemoryProperties.isPrivateRecallEnabled()).thenReturn(true);
        when(appMemoryProperties.isPrivateRecallGatingEnabled()).thenReturn(true);
        when(memoryRelevanceGate.shouldRecall("继续上次的话题")).thenReturn(true);
        when(appMemoryProperties.getPrivateRecallTopK()).thenReturn(3);
        when(appMemoryProperties.getPrivateRecallMinScore()).thenReturn(0.0f);
        when(appMemoryProperties.getPrivateRecallMaxPromptChars()).thenReturn(1200);

        VectorSearchService.SearchResult memory = new VectorSearchService.SearchResult();
        memory.setContent("[用户私人记忆] 用户偏好中文回答");
        memory.setScore(0.9f);
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

    @Test
    void chat_shouldFilterPrivateMemoriesByScoreAndPromptBudget() throws Exception {
        SessionManager.SessionInfo session = new SessionManager.SessionInfo("session-1");
        when(sessionManager.getOrCreateSession("session-1")).thenReturn(session);
        when(appMemoryProperties.isPrivateRecallEnabled()).thenReturn(true);
        when(appMemoryProperties.isPrivateRecallGatingEnabled()).thenReturn(true);
        when(memoryRelevanceGate.shouldRecall("继续上次的话题")).thenReturn(true);
        when(appMemoryProperties.getPrivateRecallTopK()).thenReturn(3);
        when(appMemoryProperties.getPrivateRecallMinScore()).thenReturn(0.5f);
        when(appMemoryProperties.getPrivateRecallMaxPromptChars()).thenReturn(20);

        VectorSearchService.SearchResult kept = new VectorSearchService.SearchResult();
        kept.setContent("[用户私人记忆] Java");
        kept.setScore(0.9f);
        VectorSearchService.SearchResult lowScore = new VectorSearchService.SearchResult();
        lowScore.setContent("[用户私人记忆] Python");
        lowScore.setScore(0.4f);
        VectorSearchService.SearchResult deleted = new VectorSearchService.SearchResult();
        deleted.setId("deleted-memory");
        deleted.setContent("[用户私人记忆] Go");
        deleted.setScore(0.95f);
        deleted.setMetadata("{\"deleted\":true}");
        VectorSearchService.SearchResult malformed = new VectorSearchService.SearchResult();
        malformed.setId("malformed-memory");
        malformed.setContent("[用户私人记忆] Rust");
        malformed.setScore(0.95f);
        malformed.setMetadata("{not-json}");
        VectorSearchService.SearchResult overBudget = new VectorSearchService.SearchResult();
        overBudget.setContent("[用户私人记忆] Kubernetes");
        overBudget.setScore(0.8f);
        when(vectorSearchService.searchSessionMemories("继续上次的话题", "session-1", 3))
                .thenReturn(List.of(kept, lowScore, deleted, malformed, overBudget));
        when(chatService.buildSystemPrompt(eq(List.of()), eq(List.of(kept)))).thenReturn("prompt-with-filtered-memory");
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

        verify(chatService).buildSystemPrompt(eq(List.of()), eq(List.of(kept)));
    }

    @Test
    void chat_shouldSkipPrivateMemorySearchForOrdinaryQuestion() throws Exception {
        SessionManager.SessionInfo session = new SessionManager.SessionInfo("session-1");
        when(sessionManager.getOrCreateSession("session-1")).thenReturn(session);
        when(appMemoryProperties.isPrivateRecallEnabled()).thenReturn(true);
        when(appMemoryProperties.isPrivateRecallGatingEnabled()).thenReturn(true);
        when(chatService.buildSystemPrompt(eq(List.of()), eq(List.of()))).thenReturn("prompt");
        when(chatService.executeChat(any(), eq("请解释什么是幂等性"))).thenReturn("好的");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"session-1\",\"Question\":\"请解释什么是幂等性\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));

        verify(vectorSearchService, never()).searchSessionMemories(anyString(), anyString(), anyInt());
        verify(chatService).buildSystemPrompt(eq(List.of()), eq(List.of()));
    }

    @Test
    void chat_shouldUseDirectModelForOrdinaryQuestionWhenEnabled() throws Exception {
        SessionManager.SessionInfo session = new SessionManager.SessionInfo("session-1");
        when(sessionManager.getOrCreateSession("session-1")).thenReturn(session);
        when(appMemoryProperties.isPrivateRecallEnabled()).thenReturn(false);
        when(appChatProperties.isDirectModelRoutingEnabled()).thenReturn(true);
        when(chatService.buildSystemPrompt(eq(List.of()), eq(List.of()))).thenReturn("prompt");
        when(chatService.executeDirectChat(any(), eq("prompt"), eq("请解释什么是幂等性"))).thenReturn("直接回答");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"session-1\",\"Question\":\"请解释什么是幂等性\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("直接回答"));

        verify(chatService).executeDirectChat(any(), eq("prompt"), eq("请解释什么是幂等性"));
        verify(chatService, never()).executeChat(any(), anyString());
    }
}
