package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppChatHistoryProperties;
import org.example.dto.ChatSessionRecord;
import org.example.dto.ChatSessionSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChatHistoryStore {

    private static final int TITLE_MAX_LENGTH = 30;

    private final ChatHistoryRepository repository;

    @Autowired
    public ChatHistoryStore(AppChatHistoryProperties properties,
                            ObjectMapper objectMapper,
                            DataSource dataSource,
                            IncidentSchemaMigrator schemaMigrator) {
        schemaMigrator.migrate();
        this.repository = new ChatHistoryRepository(dataSource);
    }

    public void appendMessagePair(String sessionId, long createTime,
                                  String userQuestion, String aiAnswer) {
        long now = System.currentTimeMillis();
        long effectiveCreateTime = createTime > 0 ? createTime : now;
        String title = titleForQuestion(userQuestion);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                repository.appendMessagePair(
                        sessionId, effectiveCreateTime, now, title, userQuestion, aiAnswer);
                return;
            } catch (IllegalStateException e) {
                if (attempt == 2 || !isDuplicateKeyViolation(e)) {
                    throw e;
                }
            }
        }
    }

    public void save(ChatSessionRecord record) {
        validate(record);
        repository.save(record, titleFor(record));
    }

    public Optional<ChatSessionRecord> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return repository.load(sessionId);
    }

    public List<ChatSessionSummary> listSessions() {
        return repository.listSessions();
    }

    public boolean delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return repository.delete(sessionId);
    }

    private boolean isDuplicateKeyViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.sql.SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void validate(ChatSessionRecord record) {
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
    }

    private String titleFor(ChatSessionRecord record) {
        for (Map<String, String> message : record.getMessageHistory()) {
            if ("user".equals(message.get("role"))) {
                String title = titleForQuestion(message.get("content"));
                if (!"新对话".equals(title)) {
                    return title;
                }
            }
        }
        return "新对话";
    }

    private String titleForQuestion(String question) {
        String content = question == null ? "" : question.trim();
        if (content.isEmpty()) {
            return "新对话";
        }
        return content.length() > TITLE_MAX_LENGTH
                ? content.substring(0, TITLE_MAX_LENGTH) + "..."
                : content;
    }
}
