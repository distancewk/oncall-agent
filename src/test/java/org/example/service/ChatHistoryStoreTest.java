package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppChatHistoryProperties;
import org.example.config.AppIncidentProperties;
import org.example.dto.ChatSessionRecord;
import org.example.dto.ChatSessionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHistoryStoreTest {

    @TempDir
    private Path historyDir;

    private ChatHistoryStore store;
    private DataSource dataSource;
    private AppChatHistoryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppChatHistoryProperties();
        properties.setPath(historyDir.toString());
        dataSource = dataSource(historyDir.resolve("chat-db"));
        store = new ChatHistoryStore(
                properties, new ObjectMapper(), dataSource, new IncidentSchemaMigrator(dataSource));
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
        assertFalse(historyDir.resolve("c2Vzc2lvbi93aXRoL3BhdGg.json").toFile().exists());

        ChatHistoryStore restarted = new ChatHistoryStore(
                properties, new ObjectMapper(), dataSource, new IncidentSchemaMigrator(dataSource));
        assertEquals(4, restarted.load("session/with/path").orElseThrow().getMessageHistory().size());
    }

    @Test
    void concurrentAppend_shouldPersistBothMessagePairs() throws Exception {
        ChatHistoryStore firstStore = new ChatHistoryStore(
                properties, new ObjectMapper(), dataSource, new IncidentSchemaMigrator(dataSource));
        ChatHistoryStore secondStore = new ChatHistoryStore(
                properties, new ObjectMapper(), dataSource, new IncidentSchemaMigrator(dataSource));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                firstStore.appendMessagePair("concurrent-session", 100L, "first question", "first answer");
                return null;
            });
            Future<?> second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                secondStore.appendMessagePair("concurrent-session", 100L, "second question", "second answer");
                return null;
            });
            start.countDown();

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            ChatSessionRecord record = store.load("concurrent-session").orElseThrow();
            List<String> contents = record.getMessageHistory().stream()
                    .map(message -> message.get("content"))
                    .toList();
            assertEquals(4, record.getMessageHistory().size());
            assertTrue(contents.contains("first question"));
            assertTrue(contents.contains("first answer"));
            assertTrue(contents.contains("second question"));
            assertTrue(contents.contains("second answer"));
        } finally {
            executor.shutdownNow();
        }
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

    private DataSource dataSource(Path path) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl("jdbc:h2:" + path);
        source.setUsername("");
        source.setPassword("");
        return source;
    }
}
