package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.ApiResponse;
import org.example.service.AiOpsService;
import org.example.service.AlertService;
import org.example.service.ChatService;
import org.example.service.SessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
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

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/chat/session/bad-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }
}
