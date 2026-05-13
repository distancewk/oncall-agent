package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppChatHistoryProperties;
import org.example.dto.ChatSessionRecord;
import org.example.dto.ChatSessionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class ChatHistoryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatHistoryStore.class);
    private static final int TITLE_MAX_LENGTH = 30;

    private final AppChatHistoryProperties properties;
    private final ObjectMapper objectMapper;

    public ChatHistoryStore(AppChatHistoryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public synchronized void appendMessagePair(String sessionId, long createTime, String userQuestion, String aiAnswer) {
        long now = System.currentTimeMillis();
        ChatSessionRecord record = load(sessionId).orElseGet(() -> newRecord(sessionId, createTime, now));
        if (record.getMessageHistory() == null) {
            record.setMessageHistory(new ArrayList<>());
        }
        record.getMessageHistory().add(Map.of("role", "user", "content", userQuestion));
        record.getMessageHistory().add(Map.of("role", "assistant", "content", aiAnswer));
        record.setUpdateTime(now);
        save(record);
    }

    public synchronized void save(ChatSessionRecord record) {
        if (record == null || record.getSessionId() == null || record.getSessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        long now = System.currentTimeMillis();
        if (record.getCreateTime() <= 0) {
            record.setCreateTime(now);
        }
        if (record.getUpdateTime() <= 0) {
            record.setUpdateTime(now);
        }
        if (record.getMessageHistory() == null) {
            record.setMessageHistory(new ArrayList<>());
        }

        try {
            Path root = rootPath();
            Files.createDirectories(root);
            Path target = pathForSession(record.getSessionId());
            Path temp = Files.createTempFile(root, "chat-history-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), record);
            moveReplacing(temp, target);
        } catch (IOException e) {
            throw new IllegalStateException("保存聊天历史失败: " + record.getSessionId(), e);
        }
    }

    public synchronized Optional<ChatSessionRecord> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Path path = pathForSession(sessionId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return readRecord(path);
    }

    public synchronized List<ChatSessionSummary> listSessions() {
        Path root = rootPath();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readRecord)
                    .flatMap(Optional::stream)
                    .map(this::toSummary)
                    .sorted(Comparator.comparingLong(ChatSessionSummary::getUpdateTime).reversed())
                    .toList();
        } catch (IOException e) {
            LOGGER.warn("读取聊天历史列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    public synchronized boolean delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            return Files.deleteIfExists(pathForSession(sessionId));
        } catch (IOException e) {
            LOGGER.warn("删除聊天历史失败: {}", e.getMessage());
            return false;
        }
    }

    private ChatSessionRecord newRecord(String sessionId, long createTime, long updateTime) {
        ChatSessionRecord record = new ChatSessionRecord();
        record.setSessionId(sessionId);
        record.setCreateTime(createTime > 0 ? createTime : updateTime);
        record.setUpdateTime(updateTime);
        record.setMessageHistory(new ArrayList<>());
        return record;
    }

    private Optional<ChatSessionRecord> readRecord(Path path) {
        try {
            ChatSessionRecord record = objectMapper.readValue(path.toFile(), ChatSessionRecord.class);
            if (record.getMessageHistory() == null) {
                record.setMessageHistory(new ArrayList<>());
            }
            return Optional.of(record);
        } catch (IOException e) {
            LOGGER.warn("读取聊天历史文件失败: {}, {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private ChatSessionSummary toSummary(ChatSessionRecord record) {
        ChatSessionSummary summary = new ChatSessionSummary();
        summary.setSessionId(record.getSessionId());
        summary.setTitle(titleFor(record));
        summary.setMessagePairCount(record.getMessageHistory().size() / 2);
        summary.setCreateTime(record.getCreateTime());
        summary.setUpdateTime(record.getUpdateTime());
        return summary;
    }

    private String titleFor(ChatSessionRecord record) {
        for (Map<String, String> message : record.getMessageHistory()) {
            if ("user".equals(message.get("role"))) {
                String content = message.getOrDefault("content", "").trim();
                if (!content.isEmpty()) {
                    return content.length() > TITLE_MAX_LENGTH
                            ? content.substring(0, TITLE_MAX_LENGTH) + "..."
                            : content;
                }
            }
        }
        return "新对话";
    }

    private Path pathForSession(String sessionId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
        return rootPath().resolve(encoded + ".json").normalize().toAbsolutePath();
    }

    private Path rootPath() {
        String configuredPath = properties.getPath();
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = "./data/chat-history";
        }
        return Paths.get(configuredPath).normalize().toAbsolutePath();
    }

    private void moveReplacing(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
