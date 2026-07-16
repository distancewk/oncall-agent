package org.example.service;

import org.example.exception.DependencyUnavailableException;
import org.example.config.AppMemoryProperties;
import org.example.dto.MemoryFact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;

import java.util.*;

/**
 * 记忆提炼服务
 * 负责异步分析即将被遗忘的对话历史，提取关键事实并存入向量库
 */
@Service
public class MemoryExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryExtractionService.class);

    @Autowired
    private DashScopeChatModel dashScopeChatModel;

    @Autowired
    private MemoryLifecycleService memoryLifecycleService;

    @Autowired
    private AppMemoryProperties memoryProperties;

    @Autowired(required = false)
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired(required = false)
    private DependencyGuard dependencyGuard;

    /**
     * 提炼对话并存储
     * @param sessionId 会话ID
     * @param historyToArchive 即将被归档/遗忘的历史消息对
     */
    public void extractAndStore(String sessionId, List<Map<String, String>> historyToArchive) {
        extractAndStoreBatch(sessionId, historyToArchive);
    }

    public void extractAndStoreBatch(String sessionId, List<Map<String, String>> historyToArchive) {
        if (historyToArchive == null || historyToArchive.isEmpty()) {
            return;
        }

        try {
            // 1. 拼接要分析的对话历史
            StringBuilder conversation = new StringBuilder();
            int maxPromptChars = memoryProperties == null ? 6_000 : memoryProperties.getExtractionMaxPromptChars();
            for (Map<String, String> msg : historyToArchive) {
                String line = msg.get("role") + ": " + msg.get("content") + "\n";
                if (conversation.length() + line.length() > maxPromptChars) {
                    break;
                }
                conversation.append(line);
            }

            // 2. 组装提炼 Prompt
            String systemText = "你是记忆分析专家。请分析以下用户和AI的对话，提取出其中长期有价值的技术事实、用户偏好、业务上下文或排障结论。\n" +
                    "规则：\n" +
                    "1. 提取的事实必须客观、清晰、简短，以第一人称（用户视角）或者第三人称陈述。\n" +
                    "2. 忽略寒暄、无意义的提问或暂时性的信息。\n" +
                    "3. 如果对话中没有任何值得长期记忆的价值，请严格且仅输出 'NONE'。\n" +
                    "4. 优先输出 JSON：{\"memories\":[{\"content\":\"...\",\"type\":\"PROFILE|WORK_CONTEXT|INCIDENT|EPHEMERAL\",\"confidence\":0.0,\"importance\":0.0,\"expiresAt\":null}]}。"
                    + "如果无法输出 JSON，才使用分号 ';' 隔开事实。";
            
            SystemMessage systemMessage = new SystemMessage(systemText);
            UserMessage userMessage = new UserMessage("对话记录如下：\n" + conversation.toString());
            
            Prompt prompt = new Prompt(Arrays.asList(systemMessage, userMessage));

            // 3. 调用大模型进行提炼
            ChatResponse response = callChatModel(prompt);
            String responseText = response.getResult().getOutput().getText();
            if (responseText == null) {
                logger.warn("会话 {} 的记忆提炼响应为空", sessionId);
                return;
            }
            List<MemoryFact> facts = parseFacts(responseText.trim());
            if (facts.isEmpty()) {
                logger.debug("会话 {} 的本次历史片段未提取出有价值记忆", sessionId);
                return;
            }

            logger.info("会话 {} 成功提取长期记忆, factCount: {}", sessionId, facts.size());
            memoryLifecycleService.storeMemories(sessionId, facts);

        } catch (Exception e) {
            logger.error("提炼长期记忆失败: SessionId={}", sessionId, e);
        }
    }

    private List<MemoryFact> parseFacts(String responseText) {
        if (responseText == null || responseText.isBlank() || "NONE".equalsIgnoreCase(responseText)) {
            return List.of();
        }
        int maxFacts = memoryProperties == null ? 8 : memoryProperties.getExtractionMaxFacts();
        LinkedHashMap<String, MemoryFact> facts = new LinkedHashMap<>();
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(responseText);
            com.fasterxml.jackson.databind.JsonNode memories = root.path("memories");
            if (memories.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode memory : memories) {
                    String content = normalize(memory.path("content").asText(""));
                    if (!content.isEmpty() && facts.size() < maxFacts) {
                        String type = normalizeType(memory.path("type").asText("WORK_CONTEXT"));
                        double confidence = clamp(memory.path("confidence").asDouble(0.5));
                        double importance = clamp(memory.path("importance").asDouble(0.5));
                        Long expiresAt = memory.path("expiresAt").isNumber()
                                ? memory.path("expiresAt").asLong() : null;
                        facts.putIfAbsent(content, new MemoryFact(content, type, confidence, importance, expiresAt));
                    }
                }
                return List.copyOf(facts.values());
            }
        } catch (Exception e) {
            logger.debug("结构化记忆解析失败，回退旧格式: {}", e.getClass().getSimpleName());
        }

        for (String value : responseText.split(";")) {
            String content = normalize(value);
            if (!content.isEmpty() && !"NONE".equalsIgnoreCase(content) && facts.size() < maxFacts) {
                facts.putIfAbsent(content, new MemoryFact(content, "WORK_CONTEXT", 0.5, 0.5, null));
            }
        }
        return List.copyOf(facts.values());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String normalizeType(String type) {
        return switch (type == null ? "" : type.trim().toUpperCase(Locale.ROOT)) {
            case "PROFILE", "WORK_CONTEXT", "INCIDENT", "EPHEMERAL" -> type.trim().toUpperCase(Locale.ROOT);
            default -> "WORK_CONTEXT";
        };
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private ChatResponse callChatModel(Prompt prompt) {
        if (dependencyGuard == null) {
            return dashScopeChatModel.call(prompt);
        }
        return dependencyGuard.execute("dashscope-chat", "memoryExtraction",
                () -> dashScopeChatModel.call(prompt),
                error -> {
                    if (error instanceof DependencyUnavailableException unavailable) {
                        throw unavailable;
                    }
                    if (error instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new RuntimeException(error);
                });
    }

}
