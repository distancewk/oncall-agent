package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.example.config.AppMemoryProperties;
import org.example.config.AppChatProperties;
import org.example.dto.ApiResponse;
import org.example.dto.ChatSessionRecord;
import org.example.dto.ChatSessionSummary;
import org.example.exception.DependencyUnavailableException;
import org.example.service.AiOpsService;
import org.example.service.AlertService;
import org.example.service.ChatService;
import org.example.service.DependencyGuard;
import org.example.service.MemoryLifecycleService;
import org.example.service.MemoryRelevanceGate;
import org.example.service.SessionManager;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    @Autowired
    private AiOpsService aiOpsService;
    
    @Autowired
    private DashScopeChatModel dashScopeChatModel;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("dashScopeChatModelAiOps")
    private DashScopeChatModel dashScopeChatModelAiOps;

    @Autowired
    private ChatService chatService;

    @Autowired(required = false)
    private ToolCallbackProvider tools;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("chatTaskExecutor")
    private Executor executor;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AlertService alertService;

    @Autowired(required = false)
    private VectorSearchService vectorSearchService;

    @Autowired(required = false)
    private AppMemoryProperties appMemoryProperties;

    @Autowired(required = false)
    private AppChatProperties appChatProperties;

    @Autowired(required = false)
    private MemoryLifecycleService memoryLifecycleService;

    @Autowired(required = false)
    private MemoryRelevanceGate memoryRelevanceGate;

    @Autowired(required = false)
    private ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private DependencyGuard dependencyGuard;

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, questionLength: {}", request.getId(),
                    request.getQuestion() == null ? 0 : request.getQuestion().length());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            // 获取或创建会话
            SessionManager.SessionInfo session = sessionManager.getOrCreateSession(request.getId());
            
            // 获取历史消息
            List<Map<String, String>> history = session.getHistory();
            logger.info("会话历史消息对数: {}", history.size() / 2);

            // 使用注入的 ChatModel
            DashScopeChatModel chatModel = dashScopeChatModel;

            // 记录可用工具
            chatService.logAvailableTools();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");
            
            List<VectorSearchService.SearchResult> privateMemories =
                    searchPrivateMemories(request.getQuestion(), session);

            // 构建系统提示词（包含历史消息）
            String systemPrompt = chatService.buildSystemPrompt(history, privateMemories);
            
            String fullAnswer;
            if (shouldUseDirectModel(request.getQuestion())) {
                logger.debug("普通问题走直接模型快速路径, questionLength: {}", request.getQuestion().length());
                fullAnswer = chatService.executeDirectChat(chatModel, systemPrompt, request.getQuestion());
            } else {
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
                fullAnswer = chatService.executeChat(agent, request.getQuestion());
            }
            
            // 更新会话历史
            session.addMessage(request.getQuestion(), fullAnswer, sessionManager);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                request.getId(), session.getMessagePairCount());
            
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));

        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(errorMessage(e))));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            SessionManager.SessionInfo session = sessionManager.getSession(request.getId());
            if (session != null) {
                boolean privateMemoryDeleted = session.clearHistory(sessionManager);
                if (!privateMemoryDeleted) {
                    return ResponseEntity.ok(ApiResponse.error("会话历史已清空，但私人记忆删除失败，请稍后重试"));
                }
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error("清空会话失败，请稍后重试"));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, questionLength: {}",
                        request.getId(), request.getQuestion() == null ? 0 : request.getQuestion().length());

                // 获取或创建会话
                SessionManager.SessionInfo session = sessionManager.getOrCreateSession(request.getId());
                
                // 获取历史消息
                List<Map<String, String>> history = session.getHistory();
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                // 使用注入的 ChatModel
                DashScopeChatModel chatModel = dashScopeChatModel;

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");

                List<VectorSearchService.SearchResult> privateMemories =
                        searchPrivateMemories(request.getQuestion(), session);
                
                // 构建系统提示词（包含历史消息）
                String systemPrompt = chatService.buildSystemPrompt(history, privateMemories);
                
                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream;
                try {
                    if (dependencyGuard != null) {
                        dependencyGuard.assertAvailable("dashscope-chat", "chatAgentStream");
                    }
                    stream = agent.stream(request.getQuestion());
                } catch (Exception e) {
                    if (!(e instanceof DependencyUnavailableException) && dependencyGuard != null) {
                        dependencyGuard.recordFailure("dashscope-chat", "chatAgentStream", e);
                    }
                    throw dashscopeDependencyError("chatAgentStream", e);
                }
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.debug("发送流式内容块, length: {}", chunk.length());
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        Throwable clientError = dashscopeDependencyError("chatAgentStream", error);
                        if (!(error instanceof DependencyUnavailableException) && dependencyGuard != null) {
                            dependencyGuard.recordFailure("dashscope-chat", "chatAgentStream", error);
                        }
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(errorMessage(clientError)), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(clientError);
                    },
                    () -> {
                        // 完成处理
                        try {
                            if (dependencyGuard != null) {
                                dependencyGuard.recordSuccess("dashscope-chat");
                            }
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}", 
                                request.getId(), fullAnswer.length());
                            
                            // 更新会话历史
                            session.addMessage(request.getQuestion(), fullAnswer, sessionManager);
                            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                                request.getId(), session.getMessagePairCount());
                            
                            // 发送完成标记
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                Throwable clientError = dashscopeDependencyError("chatAgentStream", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(errorMessage(clientError)), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(clientError);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 可选的 alertContext 参数，用于传入告警上下文
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps(@RequestBody(required = false) Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        // 从请求体中提取 alertContext
        String alertContext = null;
        String alertId = null;
        if (body != null) {
            if (body.containsKey("alertContext") && body.get("alertContext") instanceof String) {
                alertContext = (String) body.get("alertContext");
            }
            if (body.containsKey("alertId") && body.get("alertId") instanceof String) {
                alertId = (String) body.get("alertId");
            }
        }

        final String context = alertContext;
        final String aId = alertId;

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                DashScopeChatModel chatModel = dashScopeChatModelAiOps;

                ToolCallback[] toolCallbacks = tools != null ? tools.getToolCallbacks() : new ToolCallback[0];

                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content("当前接口为演示入口，不会持久化 DiagnosisRun 证据链；正式诊断请使用 /api/incidents/{incidentId}/diagnose。\n"),
                                MediaType.APPLICATION_JSON));
                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并拆解任务...\n")));

                // 调用 AiOpsService 执行分析流程（传入告警上下文）
                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks, context);

                if (overAllStateOptional.isEmpty()) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                logger.info("AI Ops 编排完成，开始提取最终报告...");

                // 提取最终报告
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                // 输出最终报告
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());

                    // 如果有关联的告警 ID，存储报告
                    if (aId != null) {
                        alertService.storeReport(aId, finalReportText);
                        logger.info("告警分析报告已关联存储, alertId: {}", aId);
                    }
                    
                    // 发送分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    
                    // 发送完整的告警分析报告
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));
                    
                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);
                        
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }
                    
                    // 发送结束分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
                    
                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("服务暂时不可用，请稍后重试", requestId()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }


    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            SessionManager.SessionInfo session = sessionManager.getSession(sessionId);
            if (session != null) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.getCreateTime());
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error("获取会话失败，请稍后重试"));
        }
    }

    @GetMapping("/chat/sessions")
    public ResponseEntity<ApiResponse<List<ChatSessionSummary>>> listChatSessions() {
        return ResponseEntity.ok(ApiResponse.success(sessionManager.listSessions()));
    }

    @GetMapping("/chat/session/{sessionId}/messages")
    public ResponseEntity<ApiResponse<ChatSessionRecord>> getSessionMessages(@PathVariable String sessionId) {
        return sessionManager.getSessionMessages(sessionId)
                .map(record -> ResponseEntity.ok(ApiResponse.success(record)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.error("会话不存在")));
    }

    @DeleteMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<String>> deleteChatSession(@PathVariable String sessionId) {
        SessionManager.SessionDeletionResult result = sessionManager.deleteSessionWithStatus(sessionId);
        if (!result.deleted()) {
            return ResponseEntity.ok(ApiResponse.error("会话不存在"));
        }
        if (!result.privateMemoryDeleted()) {
            return ResponseEntity.ok(ApiResponse.error("会话已删除，但私人记忆删除失败，请稍后重试"));
        }
        return ResponseEntity.ok(ApiResponse.success("会话已删除"));
    }

    private List<VectorSearchService.SearchResult> searchPrivateMemories(
            String question,
            SessionManager.SessionInfo session) {
        if (vectorSearchService == null || session == null) {
            return List.of();
        }
        boolean enabled = appMemoryProperties == null || appMemoryProperties.isPrivateRecallEnabled();
        if (!enabled) {
            return List.of();
        }
        boolean gatingEnabled = appMemoryProperties != null && appMemoryProperties.isPrivateRecallGatingEnabled();
        if (gatingEnabled && memoryRelevanceGate != null && !memoryRelevanceGate.shouldRecall(question)) {
            logger.debug("跳过与长期记忆无关的私人记忆召回, questionLength: {}",
                    question == null ? 0 : question.length());
            return List.of();
        }
        int memoryTopK = appMemoryProperties == null ? 3 : Math.max(1, appMemoryProperties.getPrivateRecallTopK());
        try {
            List<VectorSearchService.SearchResult> results =
                    vectorSearchService.searchSessionMemories(question, session.getSessionId(), memoryTopK);
            if (memoryLifecycleService != null) {
                return memoryLifecycleService.filterRecallResults(results);
            }
            return filterRecallResults(results);
        } catch (RuntimeException e) {
            logger.warn("检索私人长期记忆失败，继续普通对话, type: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<VectorSearchService.SearchResult> filterRecallResults(List<VectorSearchService.SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        float minScore = appMemoryProperties == null ? 0.0f : appMemoryProperties.getPrivateRecallMinScore();
        int maxPromptChars = appMemoryProperties == null ? 1200 : appMemoryProperties.getPrivateRecallMaxPromptChars();
        int usedPromptChars = 0;
        List<VectorSearchService.SearchResult> filtered = new ArrayList<>();
        for (VectorSearchService.SearchResult result : results) {
            if (result == null || result.getScore() < minScore || isDeletedMemory(result)) {
                continue;
            }
            String content = result.getContent() == null ? "" : result.getContent();
            if (usedPromptChars + content.length() > maxPromptChars) {
                continue;
            }
            filtered.add(result);
            usedPromptChars += content.length();
        }
        return filtered;
    }

    private boolean shouldUseDirectModel(String question) {
        if (appChatProperties == null || !appChatProperties.isDirectModelRoutingEnabled()
                || question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        return !(normalized.contains("时间") || normalized.contains("几点") || normalized.contains("天气")
                || normalized.contains("最新") || normalized.contains("新闻") || normalized.contains("联网")
                || normalized.contains("内部") || normalized.contains("文档") || normalized.contains("知识库")
                || normalized.contains("告警") || normalized.contains("日志") || normalized.contains("指标")
                || normalized.contains("prometheus") || normalized.contains("搜索")
                || normalized.contains("查询") || normalized.contains("工具") || normalized.contains("数据库"));
    }

    private boolean isDeletedMemory(VectorSearchService.SearchResult result) {
        if (result.getMetadata() == null || result.getMetadata().isBlank()) {
            return false;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(result.getMetadata(), METADATA_TYPE);
            return Boolean.TRUE.equals(metadata.get("deleted"));
        } catch (Exception e) {
            logger.debug("解析私人记忆 metadata 失败，按已删除跳过: id={}", result.getId(), e);
            return true;
        }
    }

    private String errorMessage(Throwable error) {
        if (error instanceof DependencyUnavailableException unavailable) {
            return "服务暂时不可用（" + unavailable.getErrorCode() + "）";
        }
        return "服务暂时不可用，请稍后重试";
    }

    private String requestId() {
        return java.util.UUID.randomUUID().toString();
    }

    private DependencyUnavailableException dashscopeDependencyError(String operation, Throwable error) {
        if (error instanceof DependencyUnavailableException unavailable) {
            return unavailable;
        }
        return new DependencyUnavailableException("dashscope-chat", operation, "DEPENDENCY_ERROR", error);
    }

    // ==================== 内部类 ====================

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;
        private String requestId;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            return error(errorMessage, null);
        }

        public static SseMessage error(String errorMessage, String requestId) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            message.setRequestId(requestId);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }
}
