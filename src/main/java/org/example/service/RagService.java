package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) 服务
 * 结合向量检索和大语言模型生成答案
 */
@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.top-k:3}")
    private int topK;

    @Value("${rag.model}")
    private String model;

    @Value("${dashscope.base-url}")
    private String baseUrl;

    private Generation generation;
    private final RagQueryRewritePolicy queryRewritePolicy = new RagQueryRewritePolicy();
    private final RagGroundingPolicy ragGroundingPolicy = new RagGroundingPolicy();

    @PostConstruct
    public void init() {
        // 设置 API Key 和 Base URL
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = baseUrl;
        
        // 创建 Generation 实例
        generation = new Generation();
        
        logger.info("RAG 服务初始化完成，model: {}, topK: {}", model, topK);
    }

    /**
     * 流式处理用户问题（不带历史消息）
     * 
     * @param question 用户问题
     * @param callback 流式回调接口
     */
    public void queryStream(String question, StreamCallback callback) {
        queryStream(question, new ArrayList<>(), callback);
    }

    /**
     * 流式处理用户问题（带历史消息）
     * 
     * @param question 用户问题
     * @param history 历史消息列表，格式：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
     * @param callback 流式回调接口
     */
    public void queryStream(String question, List<Map<String, String>> history, StreamCallback callback) {
        try {
            logger.info("收到 RAG 流式查询, questionLength: {}", question == null ? 0 : question.length());

            // 1. 从向量数据库检索相关文档
            String retrievalQuery = queryRewritePolicy.rewrite(question);
            List<VectorSearchService.SearchResult> searchResults =
                vectorSearchService.searchSimilarDocuments(retrievalQuery, topK);

            // 发送检索结果
            callback.onSearchResults(searchResults);

            if (searchResults.isEmpty()) {
                logger.warn("未找到相关文档");
                callback.onComplete("抱歉，我在知识库中没有找到相关信息来回答您的问题。", "");
                return;
            }

            // 2. 构建上下文和提示词
            String context = buildContext(searchResults);
            String prompt = buildPrompt(question, context);

            // 3. 流式调用大语言模型（传入历史消息）
            generateAnswerStream(question, prompt, history, searchResults, callback);

        } catch (Exception e) {
            logger.error("RAG 流式查询失败", e);
            callback.onError(e);
        }
    }

    /**
     * 构建上下文
     */
    private String buildContext(List<VectorSearchService.SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();
        
        for (int i = 0; i < searchResults.size(); i++) {
            VectorSearchService.SearchResult result = searchResults.get(i);
            String sourceId = result.getId() == null || result.getId().isBlank()
                    ? "document-" + (i + 1) : result.getId();
            context.append("【参考资料 ").append(i + 1).append("，来源: ")
                    .append(sourceId).append("】\n");
            context.append(result.getContent()).append("\n\n");
        }
        
        return context.toString();
    }

    /**
     * 构建提示词
     */
    private String buildPrompt(String question, String context) {
        return String.format(
            "你是一个专业的AI助手。请根据以下参考资料回答用户的问题。\n\n" +
            "参考资料：\n%s\n" +
            "用户问题：%s\n\n" +
            "请基于上述参考资料给出准确、详细的回答。每个依赖资料的事实后必须使用 " +
                    "[来源: <id>] 引用对应资料的来源 id，不能编造 id。若资料中没有相关信息，请明确说明知识库没有找到依据。",
            context, question
        );
    }

    /**
     * 生成答案（流式）
     * 
     * @param prompt 当前问题的提示词
     * @param history 历史消息列表
     * @param callback 流式回调接口
     */
    private void generateAnswerStream(String question, String prompt,
                                      List<Map<String, String>> history,
                                      List<VectorSearchService.SearchResult> searchResults,
                                      StreamCallback callback)
            throws NoApiKeyException, ApiException, InputRequiredException {
        
        // 构建消息列表：历史消息 + 当前问题
        List<Message> messages = new ArrayList<>();
        
        // 添加历史消息
        for (Map<String, String> historyMsg : history) {
            String role = historyMsg.get("role");
            String content = historyMsg.get("content");
            
            if ("user".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.USER.getValue())
                        .content(content)
                        .build());
            } else if ("assistant".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.ASSISTANT.getValue())
                        .content(content)
                        .build());
            }
        }
        
        // 添加当前用户问题
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build();
        messages.add(userMsg);
        
        logger.debug("发送给AI模型的消息数量: {}（包含 {} 条历史消息）", 
            messages.size(), history.size());

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .incrementalOutput(true)
                .resultFormat("message")
                .messages(messages)
                .build();

        logger.info("开始调用AI模型流式接口...");
        
        Flowable<GenerationResult> result = generation.streamCall(param);
        
        StringBuilder reasoningContent = new StringBuilder();
        StringBuilder finalContent = new StringBuilder();
        boolean bufferForGrounding = ragGroundingPolicy.requiresCitation(question);
        
        logger.info("开始接收AI模型流式响应...");

        result.blockingForEach(message -> {
            if (message.getOutput() != null && 
                message.getOutput().getChoices() != null && 
                !message.getOutput().getChoices().isEmpty()) {
                
                // 获取消息内容
                // 注意：qwen3-30b-a3b-thinking-2507 模型会在 content 中返回完整内容
                // reasoning 部分可能需要通过特殊方式提取或者直接包含在 content 中
                var firstChoice = message.getOutput().getChoices().get(0);
                if (firstChoice.getMessage() != null) {
                    String content = firstChoice.getMessage().getContent();

                    if (content != null && !content.isEmpty()) {
                        logger.debug("收到AI模型内容块, length: {}", content.length());

                        // 对于 thinking 模型，content 可能包含思考过程和最终答案
                        // 这里我们将所有内容都作为答案返回
                        finalContent.append(content);
                        if (!bufferForGrounding) {
                            callback.onContentChunk(content);
                        }

                        if (!bufferForGrounding) {
                            logger.debug("已调用 onContentChunk 回调");
                        }
                    } else {
                        logger.debug("收到空内容块，跳过");
                    }
                }
            }
        });
        
        logger.info("AI模型流式响应完成，总内容长度: {}", finalContent.length());

        callback.onComplete(ragGroundingPolicy.enforce(
                question, finalContent.toString(), sourceIds(searchResults)),
                reasoningContent.toString());
        logger.info("已调用 onComplete 回调");
    }

    private java.util.Set<String> sourceIds(List<VectorSearchService.SearchResult> results) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (int index = 0; index < results.size(); index++) {
            String id = results.get(index).getId();
            ids.add(id == null || id.isBlank() ? "document-" + (index + 1) : id);
        }
        return ids;
    }

    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        void onSearchResults(List<VectorSearchService.SearchResult> results);
        void onReasoningChunk(String chunk);
        void onContentChunk(String chunk);
        void onComplete(String fullContent, String fullReasoning);
        void onError(Exception e);
    }
}
