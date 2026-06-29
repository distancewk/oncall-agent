package org.example.service;

import org.example.dto.ChatSessionRecord;
import org.example.dto.ChatSessionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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

    @Autowired
    @Qualifier("memoryTaskExecutor")
    private Executor memoryExecutor;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private ChatHistoryStore chatHistoryStore;

    @Autowired(required = false)
    private MemoryLifecycleService memoryLifecycleService;

    private static final String REDIS_KEY_PREFIX = "session:";
    private static final long SESSION_TTL_SECONDS = 3600; // 1 hour

    // 存储会话信息
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> memoryGenerations = new ConcurrentHashMap<>();
    private final Map<String, Object> memoryOperationLocks = new ConcurrentHashMap<>();

    /**
     * 获取或创建会话
     */
    public SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        // Check L1 cache first
        SessionInfo existing = sessions.get(sessionId);
        if (existing != null) {
            refreshRedisTtl(sessionId);
            return existing;
        }
        // Check Redis persistence
        SessionInfo fromRedis = loadFromRedis(sessionId);
        if (fromRedis != null) {
            sessions.put(sessionId, fromRedis);
            return fromRedis;
        }
        SessionInfo fromHistoryStore = loadFromHistoryStore(sessionId);
        if (fromHistoryStore != null) {
            sessions.put(sessionId, fromHistoryStore);
            saveToRedis(fromHistoryStore);
            return fromHistoryStore;
        }
        // Create new
        SessionInfo session = newActiveSession(sessionId);
        sessions.put(sessionId, session);
        saveToRedis(session);
        return session;
    }

    /**
     * 获取指定会话，如果不存在则返回null
     */
    public SessionInfo getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        // Check L1 cache first
        SessionInfo session = sessions.get(sessionId);
        if (session != null) {
            refreshRedisTtl(sessionId);
            return session;
        }
        // Check Redis
        SessionInfo fromRedis = loadFromRedis(sessionId);
        if (fromRedis != null) {
            sessions.put(sessionId, fromRedis);
            return fromRedis;
        }
        SessionInfo fromHistoryStore = loadFromHistoryStore(sessionId);
        if (fromHistoryStore != null) {
            sessions.put(sessionId, fromHistoryStore);
            saveToRedis(fromHistoryStore);
            return fromHistoryStore;
        }
        return null;
    }

    /**
     * Persist session changes to Redis. Called by SessionInfo after mutations.
     */
    public void saveSession(SessionInfo session) {
        saveToRedis(session);
    }

    private void saveToRedis(SessionInfo session) {
        if (redisTemplate == null || objectMapper == null) {
            return; // Redis not configured, use in-memory only
        }
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", session.getSessionId());
            data.put("createTime", session.getCreateTime());
            data.put("messageHistory", session.getHistory());
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + session.getSessionId(),
                json,
                SESSION_TTL_SECONDS,
                TimeUnit.SECONDS
            );
        } catch (Exception e) {
            logger.warn("Failed to save session to Redis: {}", e.getMessage());
        }
    }

    private SessionInfo loadFromRedis(String sessionId) {
        if (redisTemplate == null || objectMapper == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + sessionId);
            if (json == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(json, Map.class);
            long createTime = System.currentTimeMillis();
            Object createTimeVal = data.get("createTime");
            if (createTimeVal instanceof Number) {
                createTime = ((Number) createTimeVal).longValue();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> history = (List<Map<String, String>>) data.get("messageHistory");
            return newActiveSession(sessionId, createTime, history);
        } catch (Exception e) {
            logger.warn("Failed to load session from Redis: {}", e.getMessage());
            return null;
        }
    }

    private void refreshRedisTtl(String sessionId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.expire(REDIS_KEY_PREFIX + sessionId, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Non-critical, ignore
        }
    }

    private SessionInfo loadFromHistoryStore(String sessionId) {
        if (chatHistoryStore == null) {
            return null;
        }
        return chatHistoryStore.load(sessionId)
                .map(record -> newActiveSession(
                        record.getSessionId(),
                        record.getCreateTime(),
                        recentWindow(record.getMessageHistory())
                ))
                .orElse(null);
    }

    public List<ChatSessionSummary> listSessions() {
        if (chatHistoryStore == null) {
            return sessions.values().stream()
                    .map(this::summaryFromSession)
                    .sorted(Comparator.comparingLong(ChatSessionSummary::getUpdateTime).reversed())
                    .toList();
        }
        return chatHistoryStore.listSessions();
    }

    public Optional<ChatSessionRecord> getSessionMessages(String sessionId) {
        if (chatHistoryStore != null) {
            Optional<ChatSessionRecord> persisted = chatHistoryStore.load(sessionId);
            if (persisted.isPresent()) {
                return persisted;
            }
        }
        SessionInfo session = getSession(sessionId);
        return session == null ? Optional.empty() : Optional.of(recordFromSession(session));
    }

    public boolean deleteSession(String sessionId) {
        SessionDeletionResult result = deleteSessionWithStatus(sessionId);
        return result.deleted() && result.privateMemoryDeleted();
    }

    public SessionDeletionResult deleteSessionWithStatus(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new SessionDeletionResult(false, true);
        }
        advanceMemoryGeneration(sessionId);
        boolean removedFromMemory = sessions.remove(sessionId) != null;
        deleteFromRedis(sessionId);
        boolean removedFromHistory = chatHistoryStore != null && chatHistoryStore.delete(sessionId);
        boolean deleted = removedFromMemory || removedFromHistory;
        boolean privateMemoryDeleted = !deleted || deleteSessionMemories(sessionId);
        return new SessionDeletionResult(deleted, privateMemoryDeleted);
    }

    private SessionInfo newActiveSession(String sessionId) {
        return newActiveSession(sessionId, System.currentTimeMillis(), new ArrayList<>());
    }

    private SessionInfo newActiveSession(String sessionId, long createTime, List<Map<String, String>> initialHistory) {
        return new SessionInfo(sessionId, createTime, initialHistory, advanceMemoryGeneration(sessionId));
    }

    private void appendToHistoryStore(String sessionId, long createTime, String userQuestion, String aiAnswer) {
        if (chatHistoryStore == null) {
            return;
        }
        try {
            chatHistoryStore.appendMessagePair(sessionId, createTime, userQuestion, aiAnswer);
        } catch (RuntimeException e) {
            logger.warn("保存完整聊天历史失败: {}", e.getMessage());
        }
    }

    private void deletePersistedHistory(String sessionId) {
        deleteFromRedis(sessionId);
        if (chatHistoryStore != null) {
            chatHistoryStore.delete(sessionId);
        }
    }

    boolean deleteSessionMemories(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || memoryLifecycleService == null) {
            return true;
        }
        Object lock = memoryOperationLock(sessionId);
        synchronized (lock) {
            RuntimeException lastFailure = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    memoryLifecycleService.deleteSessionMemories(sessionId);
                    memoryOperationLocks.remove(sessionId, lock);
                    return true;
                } catch (RuntimeException e) {
                    lastFailure = e;
                    logger.warn("Failed to delete private memories for session: {}, attempt: {}",
                            sessionId, attempt, e);
                }
            }
            logger.error("Private memory deletion failed after retries for session: {}", sessionId, lastFailure);
            memoryOperationLocks.remove(sessionId, lock);
            return false;
        }
    }

    private synchronized long currentMemoryGeneration(String sessionId) {
        return memoryGenerations.getOrDefault(sessionId, 0L);
    }

    private synchronized long advanceMemoryGeneration(String sessionId) {
        return memoryGenerations.merge(sessionId, 1L, Long::sum);
    }

    private synchronized long advanceMemoryGenerationIfCurrent(String sessionId, long expectedGeneration) {
        long currentGeneration = currentMemoryGeneration(sessionId);
        if (currentGeneration != expectedGeneration) {
            return -1L;
        }
        return advanceMemoryGeneration(sessionId);
    }

    private Object memoryOperationLock(String sessionId) {
        return memoryOperationLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    private void deleteFromRedis(String sessionId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(REDIS_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            logger.warn("Failed to delete session from Redis: {}", e.getMessage());
        }
    }

    private List<Map<String, String>> recentWindow(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return new ArrayList<>();
        }
        int maxMessages = MAX_WINDOW_SIZE * 2;
        int start = Math.max(0, history.size() - maxMessages);
        List<Map<String, String>> window = new ArrayList<>();
        for (int i = start; i < history.size(); i++) {
            window.add(new HashMap<>(history.get(i)));
        }
        return window;
    }

    private ChatSessionRecord recordFromSession(SessionInfo session) {
        ChatSessionRecord record = new ChatSessionRecord();
        record.setSessionId(session.getSessionId());
        record.setCreateTime(session.getCreateTime());
        record.setUpdateTime(System.currentTimeMillis());
        record.setMessageHistory(session.getHistory());
        return record;
    }

    private ChatSessionSummary summaryFromSession(SessionInfo session) {
        ChatSessionSummary summary = new ChatSessionSummary();
        summary.setSessionId(session.getSessionId());
        summary.setTitle(titleFromHistory(session.getHistory()));
        summary.setMessagePairCount(session.getMessagePairCount());
        summary.setCreateTime(session.getCreateTime());
        summary.setUpdateTime(session.getCreateTime());
        return summary;
    }

    private String titleFromHistory(List<Map<String, String>> history) {
        for (Map<String, String> message : history) {
            if ("user".equals(message.get("role"))) {
                String content = message.getOrDefault("content", "").trim();
                if (!content.isEmpty()) {
                    return content.length() > 30 ? content.substring(0, 30) + "..." : content;
                }
            }
        }
        return "新对话";
    }

    public record SessionDeletionResult(boolean deleted, boolean privateMemoryDeleted) {
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
        private volatile long memoryGeneration;

        public SessionInfo(String sessionId) {
            this(sessionId, System.currentTimeMillis(), new ArrayList<>(), 0L);
        }

        private SessionInfo(String sessionId, long createTime, List<Map<String, String>> initialHistory) {
            this(sessionId, createTime, initialHistory, 0L);
        }

        private SessionInfo(String sessionId,
                            long createTime,
                            List<Map<String, String>> initialHistory,
                            long memoryGeneration) {
            this.sessionId = sessionId;
            this.messageHistory = new ArrayList<>();
            replaceHistory(initialHistory);
            this.createTime = createTime > 0 ? createTime : System.currentTimeMillis();
            this.lock = new ReentrantLock();
            this.memoryGeneration = memoryGeneration;
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
            List<Map<String, String>> evictedMessages = new ArrayList<>();
            lock.lock();
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

            // 持久化到 Redis（异步，非阻塞）
            if (manager != null) {
                manager.appendToHistoryStore(sessionId, createTime, userQuestion, aiAnswer);
                manager.saveSession(this);
            }

            // 触发异步提炼
            if (!evictedMessages.isEmpty() && manager != null) {
                manager.triggerMemoryExtraction(sessionId, evictedMessages, memoryGeneration);
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
         * 清空历史消息并持久化
         */
        public boolean clearHistory(SessionManager manager) {
            long nextGeneration = -1L;
            if (manager != null) {
                nextGeneration = manager.advanceMemoryGenerationIfCurrent(sessionId, memoryGeneration);
                if (nextGeneration < 0) {
                    return true;
                }
            }
            lock.lock();
            try {
                if (manager != null) {
                    memoryGeneration = nextGeneration;
                }
                messageHistory.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
                if (manager != null) {
                    manager.deletePersistedHistory(sessionId);
                }
            } finally {
                lock.unlock();
            }
            if (manager != null) {
                return manager.deleteSessionMemories(sessionId);
            }
            return true;
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

        private void replaceHistory(List<Map<String, String>> history) {
            messageHistory.clear();
            if (history == null) {
                return;
            }
            for (Map<String, String> entry : history) {
                messageHistory.add(new HashMap<>(entry));
            }
        }
    }

    /**
     * 触发异步的记忆提炼
     */
    public void triggerMemoryExtraction(String sessionId, List<Map<String, String>> historyToArchive) {
        if (sessionId == null || sessionId.isBlank() || memoryExtractionService == null || memoryExecutor == null) {
            return;
        }
        triggerMemoryExtraction(sessionId, historyToArchive, currentMemoryGeneration(sessionId));
    }

    private void triggerMemoryExtraction(String sessionId,
                                         List<Map<String, String>> historyToArchive,
                                         long generation) {
        if (sessionId == null || sessionId.isBlank() || memoryExtractionService == null || memoryExecutor == null) {
            return;
        }
        memoryExecutor.execute(() -> {
            synchronized (memoryOperationLock(sessionId)) {
                if (generation != currentMemoryGeneration(sessionId)) {
                    logger.debug("跳过过期会话记忆提炼任务: sessionId={}", sessionId);
                    return;
                }
                memoryExtractionService.extractAndStore(sessionId, historyToArchive);
            }
        });
    }
}
