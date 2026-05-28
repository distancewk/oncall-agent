package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.exception.DependencyUnavailableException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.util.ToolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired(required = false)
    private ToolCallbackProvider tools;

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired(required = false)
    private DependencyGuard dependencyGuard;

    @Autowired(required = false)
    private McpToolCallbackGuard mcpToolCallbackGuard;

    /**
     * 构建系统提示词（包含历史消息）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        return buildSystemPrompt(history, List.of());
    }

    public String buildSystemPrompt(List<Map<String, String>> history,
                                    List<VectorSearchService.SearchResult> privateMemories) {
        StringBuilder systemPromptBuilder = new StringBuilder();

        // 从资源文件加载基础系统提示
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/chat-system-prompt.txt");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String basePrompt = reader.lines().collect(Collectors.joining("\n"));
                systemPromptBuilder.append(basePrompt).append("\n\n");
            }
        } catch (Exception e) {
            logger.warn("无法加载系统提示词资源文件，使用默认提示词", e);
            systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询内部文档知识库，以及查询 Prometheus 告警信息。\n\n");
        }

        // 添加私人长期记忆
        if (privateMemories != null && !privateMemories.isEmpty()) {
            systemPromptBuilder.append("--- 私人记忆 ---\n");
            for (VectorSearchService.SearchResult memory : privateMemories) {
                if (memory.getContent() != null && !memory.getContent().isBlank()) {
                    systemPromptBuilder.append("- ").append(memory.getContent()).append("\n");
                }
            }
            systemPromptBuilder.append("--- 私人记忆结束 ---\n\n");
        }

        // 添加历史消息
        if (history != null && !history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }

        systemPromptBuilder.append("请基于以上对话历史，回答用户的新问题。");

        return systemPromptBuilder.toString();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    public Object[] buildMethodToolsArray() {
        return ToolUtils.buildGeneralChatMethodToolsArray(dateTimeTools, internalDocsTools);
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        if (tools == null) {
            return new ToolCallback[0];
        }
        ToolCallback[] callbacks = Arrays.stream(tools.getToolCallbacks())
                .filter(this::isGeneralChatMcpTool)
                .toArray(ToolCallback[]::new);
        return mcpToolCallbackGuard == null ? callbacks : mcpToolCallbackGuard.wrapCallbacks(callbacks);
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        if (tools == null) {
            logger.info("MCP工具未配置，无可用工具");
            return;
        }
        ToolCallback[] toolCallbacks = getToolCallbacks();
        logger.info("普通聊天可用 MCP 工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    private boolean isGeneralChatMcpTool(ToolCallback toolCallback) {
        if (toolCallback == null || toolCallback.getToolDefinition() == null) {
            return false;
        }
        String haystack = (toolCallback.getToolDefinition().name() + " "
                + toolCallback.getToolDefinition().description() + " "
                + toolCallback.getClass().getName()).toLowerCase();
        return haystack.contains("tavily");
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        AssistantMessage response = callAgent(agent, question);
        String answer = "";
        if (response != null) {
            String responseText = response.getText();
            if (responseText != null) {
                answer = responseText;
            }
        }
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }

    private AssistantMessage callAgent(ReactAgent agent, String question) throws GraphRunnerException {
        if (dependencyGuard == null) {
            return agent.call(question);
        }
        try {
            return dependencyGuard.execute("dashscope-chat", "chatAgentCall",
                    () -> {
                        try {
                            return agent.call(question);
                        } catch (GraphRunnerException e) {
                            throw new GraphRunnerCallException(e);
                        }
                    },
                    error -> {
                        if (error instanceof DependencyUnavailableException unavailable) {
                            throw unavailable;
                        }
                        throw new DependencyUnavailableException(
                                "dashscope-chat", "chatAgentCall", "DEPENDENCY_ERROR", error);
                    });
        } catch (GraphRunnerCallException e) {
            throw e.getGraphRunnerException();
        }
    }

    private static class GraphRunnerCallException extends RuntimeException {
        private final GraphRunnerException graphRunnerException;

        private GraphRunnerCallException(GraphRunnerException graphRunnerException) {
            super(graphRunnerException);
            this.graphRunnerException = graphRunnerException;
        }

        private GraphRunnerException getGraphRunnerException() {
            return graphRunnerException;
        }
    }
}
