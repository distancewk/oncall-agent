package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppChatHistoryProperties;
import org.example.dto.ChatSessionRecord;
import org.example.dto.ChatSessionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHistoryStoreTest {

    @TempDir
    private Path historyDir;

    private ChatHistoryStore store;

    @BeforeEach
    void setUp() {
        AppChatHistoryProperties properties = new AppChatHistoryProperties();
        properties.setPath(historyDir.toString());
        store = new ChatHistoryStore(properties, new ObjectMapper());
    }

    @Test
    void appendMessagePair_shouldPersistAndReloadCompleteHistory() {
        store.appendMessagePair("session/with/path", 100L, "hello", "hi");
        store.appendMessagePair("session/with/path", 100L, "second", "answer");

        ChatSessionRecord record = store.load("session/with/path").orElseThrow();

        assertEquals("session/with/path", record.getSessionId());
        assertEquals(100L, record.getCreateTime());
        assertEquals(4, record.getMessageHistory().size());
        assertEquals("hello", record.getMessageHistory().get(0).get("content"));
        assertEquals("answer", record.getMessageHistory().get(3).get("content"));
    }

    @Test
    void listSessions_shouldReturnSummariesSortedByUpdateTimeDesc() {
        store.save(record("older", 100L, 200L, "old question"));
        store.save(record("newer", 100L, 300L, "new question"));

        List<ChatSessionSummary> summaries = store.listSessions();

        assertEquals(2, summaries.size());
        assertEquals("newer", summaries.get(0).getSessionId());
        assertEquals("new question", summaries.get(0).getTitle());
        assertEquals(1, summaries.get(0).getMessagePairCount());
        assertEquals("older", summaries.get(1).getSessionId());
    }

    @Test
    void delete_shouldRemovePersistedSession() {
        store.appendMessagePair("to-delete", 100L, "hello", "hi");

        assertTrue(store.load("to-delete").isPresent());

        store.delete("to-delete");

        assertFalse(store.load("to-delete").isPresent());
    }

    private ChatSessionRecord record(String sessionId, long createTime, long updateTime, String firstQuestion) {
        ChatSessionRecord record = new ChatSessionRecord();
        record.setSessionId(sessionId);
        record.setCreateTime(createTime);
        record.setUpdateTime(updateTime);
        record.setMessageHistory(new ArrayList<>(List.of(
                Map.of("role", "user", "content", firstQuestion),
                Map.of("role", "assistant", "content", "answer")
        )));
        return record;
    }
}
