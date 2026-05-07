package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
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
    private VectorEmbeddingService embeddingService;

    @Autowired
    private MilvusServiceClient milvusClient;

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
            DashScopeChatModel chatModel = dashScopeChatModel;
            
            ChatResponse response = chatModel.call(prompt);
            String extractedFacts = response.getResult().getOutput().getContent().trim();

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
                    storeMemoryToMilvus(sessionId, fact);
                }
            }

        } catch (Exception e) {
            logger.error("提炼长期记忆失败: SessionId={}", sessionId, e);
        }
    }

    /**
     * 将单条记忆存入 Milvus
     */
    private void storeMemoryToMilvus(String sessionId, String fact) {
        try {
            // 生成向量
            String content = "[用户私人记忆] " + fact;
            List<Float> vector = embeddingService.generateEmbedding(content);

            // 准备元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("_source", "chat_memory");
            metadata.put("session_id", sessionId);
            metadata.put("timestamp", System.currentTimeMillis());

            // 确保 collection 已加载
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                logger.error("加载 collection 失败: {}", loadResponse.getMessage());
                return;
            }

            // 生成唯一 ID
            String id = UUID.randomUUID().toString();

            // 构建插入参数
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            R<MutationResult> insertResponse = milvusClient.insert(insertParam);

            if (insertResponse.getStatus() != 0) {
                logger.error("长期记忆写入 Milvus 失败: {}", insertResponse.getMessage());
            } else {
                logger.debug("长期记忆成功写入 Milvus，ID: {}", id);
            }

        } catch (Exception e) {
            logger.error("长期记忆写入 Milvus 时发生异常", e);
        }
    }
}
