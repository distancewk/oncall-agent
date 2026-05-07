package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话管理器
 * 负责管理所有对话的上下文记录
 * 未来可以无缝切换为 Redis 等分布式存储
 */
@Service
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    // 最大历史消息窗口大小（成对计算：用户消息+AI回复=1对）
    private static final int MAX_WINDOW_SIZE = 6;

    @Autowired
    private MemoryExtractionService memoryExtractionService;

    @Autowired
    @Qualifier("chatTaskExecutor")
    private Executor executor;

    // 存储会话信息
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    /**
     * 获取或创建会话
     */
    public SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        return sessions.computeIfAbsent(sessionId, SessionInfo::new);
    }

    /**
     * 获取指定会话，如果不存在则返回null
     */
    public SessionInfo getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        return sessions.get(sessionId);
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息实体
     */
    public static class SessionInfo {
        private final String sessionId;
        // 存储历史消息对：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
        private final List<Map<String, String>> messageHistory;
        private final long createTime;
        private final ReentrantLock lock;

        public SessionInfo(String sessionId) {
            this.sessionId = sessionId;
            this.messageHistory = new ArrayList<>();
            this.createTime = System.currentTimeMillis();
            this.lock = new ReentrantLock();
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getCreateTime() {
            return createTime;
        }

        /**
         * 添加一对消息（用户问题 + AI回复）
         */
        public void addMessage(String userQuestion, String aiAnswer, SessionManager manager) {
            lock.lock();
            List<Map<String, String>> evictedMessages = new ArrayList<>();
            try {
                // 添加用户消息
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userQuestion);
                messageHistory.add(userMsg);

                // 添加AI回复
                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", aiAnswer);
                messageHistory.add(assistantMsg);

                // 自动清理：保持最多 MAX_WINDOW_SIZE 对消息
                int maxMessages = MAX_WINDOW_SIZE * 2;
                while (messageHistory.size() > maxMessages) {
                    Map<String, String> removedUser = messageHistory.remove(0); // 删除最旧的用户消息
                    evictedMessages.add(removedUser);
                    if (!messageHistory.isEmpty()) {
                        Map<String, String> removedAi = messageHistory.remove(0); // 删除对应的AI回复
                        evictedMessages.add(removedAi);
                    }
                }

                logger.debug("会话 {} 更新历史消息，当前消息对数: {}",
                        sessionId, messageHistory.size() / 2);

            } finally {
                lock.unlock();
            }

            // 触发异步提炼
            if (!evictedMessages.isEmpty() && manager != null) {
                manager.triggerMemoryExtraction(sessionId, evictedMessages);
            }
        }

        /**
         * 获取历史消息（返回副本）
         */
        public List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(messageHistory);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 清空历史消息
         */
        public void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取当前消息对数
         */
        public int getMessagePairCount() {
            lock.lock();
            try {
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 触发异步的记忆提炼
     */
    public void triggerMemoryExtraction(String sessionId, List<Map<String, String>> historyToArchive) {
        if (memoryExtractionService == null || executor == null) {
            return;
        }
        executor.execute(() -> {
            memoryExtractionService.extractAndStore(sessionId, historyToArchive);
        });
    }
}
