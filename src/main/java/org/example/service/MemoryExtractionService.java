package org.example.service;

import org.example.exception.DependencyUnavailableException;
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

    @Autowired(required = false)
    private DependencyGuard dependencyGuard;

    /**
     * 提炼对话并存储
     * @param sessionId 会话ID
     * @param historyToArchive 即将被归档/遗忘的历史消息对
     */
    public void extractAndStore(String sessionId, List<Map<String, String>> historyToArchive) {
        if (historyToArchive == null || historyToArchive.isEmpty()) {
            return;
        }

        try {
            // 1. 拼接要分析的对话历史
            StringBuilder conversation = new StringBuilder();
            for (Map<String, String> msg : historyToArchive) {
                conversation.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
            }

            // 2. 组装提炼 Prompt
            String systemText = "你是记忆分析专家。请分析以下用户和AI的对话，提取出其中长期有价值的技术事实、用户偏好、业务上下文或排障结论。\n" +
                    "规则：\n" +
                    "1. 提取的事实必须客观、清晰、简短，以第一人称（用户视角）或者第三人称陈述。\n" +
                    "2. 忽略寒暄、无意义的提问或暂时性的信息。\n" +
                    "3. 如果对话中没有任何值得长期记忆的价值，请严格且仅输出 'NONE'。\n" +
                    "4. 如果有多个事实，请用分号 ';' 隔开。";
            
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
            String extractedFacts = responseText.trim();

            if ("NONE".equalsIgnoreCase(extractedFacts) || extractedFacts.isEmpty()) {
                logger.debug("会话 {} 的本次历史片段未提取出有价值记忆", sessionId);
                return;
            }

            logger.info("会话 {} 成功提取出长期记忆: {}", sessionId, extractedFacts);

            // 4. 将提取的事实拆分并存入向量库
            String[] facts = extractedFacts.split(";");
            for (String fact : facts) {
                fact = fact.trim();
                if (!fact.isEmpty()) {
                    memoryLifecycleService.storeMemory(sessionId, fact);
                }
            }

        } catch (Exception e) {
            logger.error("提炼长期记忆失败: SessionId={}", sessionId, e);
        }
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
