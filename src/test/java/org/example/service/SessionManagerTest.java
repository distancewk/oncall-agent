package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppChatHistoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    private SessionManager sessionManager;

    @Mock
    private MemoryExtractionService memoryExtractionService;

    @Mock
    private java.util.concurrent.Executor executor;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager();
        setField(sessionManager, "memoryExtractionService", memoryExtractionService);
        setField(sessionManager, "executor", executor);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getOrCreateSession_shouldCreateNewSessionWithGeneratedId_whenSessionIdIsNull() {
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession(null);
        assertNotNull(session);
        assertNotNull(session.getSessionId());
    }

    @Test
    void getOrCreateSession_shouldCreateNewSessionWithGeneratedId_whenSessionIdIsEmpty() {
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("");
        assertNotNull(session);
        assertNotNull(session.getSessionId());
    }

    @Test
    void getOrCreateSession_shouldReturnExistingSession_whenSessionIdExists() {
        SessionManager.SessionInfo session1 = sessionManager.getOrCreateSession("test-session");
        SessionManager.SessionInfo session2 = sessionManager.getOrCreateSession("test-session");
        assertSame(session1, session2);
    }

    @Test
    void getSession_shouldReturnNull_whenSessionDoesNotExist() {
        SessionManager.SessionInfo session = sessionManager.getSession("non-existent");
        assertNull(session);
    }

    @Test
    void getSession_shouldReturnNull_whenSessionIdIsNull() {
        SessionManager.SessionInfo session = sessionManager.getSession(null);
        assertNull(session);
    }

    @Test
    void getSession_shouldReturnNull_whenSessionIdIsEmpty() {
        SessionManager.SessionInfo session = sessionManager.getSession("");
        assertNull(session);
    }

    @Test
    void getSession_shouldReturnSession_whenSessionExists() {
        SessionManager.SessionInfo created = sessionManager.getOrCreateSession("existing-session");
        SessionManager.SessionInfo found = sessionManager.getSession("existing-session");
        assertSame(created, found);
    }

    @Test
    void sessionInfo_shouldStartWithEmptyHistory() {
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("empty-test");
        assertEquals(0, session.getMessagePairCount());
        assertTrue(session.getHistory().isEmpty());
    }

    @Test
    void sessionInfo_shouldAddMessagesAndIncrementPairCount() {
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("msg-test");
        session.addMessage("hello", "hi there", sessionManager);
        assertEquals(1, session.getMessagePairCount());
    }

    @Test
    void sessionInfo_getHistory_shouldReturnCopyOfMessages() {
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("copy-test");
        session.addMessage("q1", "a1", sessionManager);

        List<Map<String, String>> history = session.getHistory();
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).get("role"));
        assertEquals("q1", history.get(0).get("content"));
        assertEquals("assistant", history.get(1).get("role"));
        assertEquals("a1", history.get(1).get("content"));

        // Verify it's a defensive copy
        history.clear();
        assertEquals(1, session.getMessagePairCount());
    }

    @Test
    void sessionInfo_clearHistory_shouldRemoveAllMessages() {
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("clear-test");
        session.addMessage("q1", "a1", sessionManager);
        session.addMessage("q2", "a2", sessionManager);
        assertEquals(2, session.getMessagePairCount());

        session.clearHistory();
        assertEquals(0, session.getMessagePairCount());
        assertTrue(session.getHistory().isEmpty());
    }

    @Test
    void sessionInfo_shouldEvictOldestMessages_whenExceedingMaxWindowSize() {
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("evict-test");
        // MAX_WINDOW_SIZE is 6, so add 7 pairs to trigger eviction
        for (int i = 1; i <= 7; i++) {
            session.addMessage("q" + i, "a" + i, sessionManager);
        }
        // After eviction: 6 pairs remain (newest 6)
        assertEquals(6, session.getMessagePairCount());

        // The oldest pair (q1/a1) should have been evicted
        List<Map<String, String>> history = session.getHistory();
        assertEquals("q2", history.get(0).get("content"));
    }

    @Test
    void sessionInfo_createTime_shouldBeSetOnCreation() {
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("time-test");
        assertTrue(session.getCreateTime() > 0);
        assertTrue(session.getCreateTime() <= System.currentTimeMillis());
    }

    @Test
    void sessionInfo_shouldPersistCompleteHistoryAndRestoreRecentWindowAfterRestart(@TempDir Path historyDir) {
        ChatHistoryStore store = newHistoryStore(historyDir);
        setField(sessionManager, "chatHistoryStore", store);

        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("persist-test");
        for (int i = 1; i <= 8; i++) {
            session.addMessage("q" + i, "a" + i, sessionManager);
        }

        assertEquals(6, session.getMessagePairCount());
        assertEquals(16, store.load("persist-test").orElseThrow().getMessageHistory().size());
        assertEquals("q1", store.load("persist-test").orElseThrow().getMessageHistory().get(0).get("content"));

        SessionManager restarted = new SessionManager();
        setField(restarted, "memoryExtractionService", memoryExtractionService);
        setField(restarted, "executor", executor);
        setField(restarted, "chatHistoryStore", store);

        SessionManager.SessionInfo restored = restarted.getOrCreateSession("persist-test");

        assertEquals(6, restored.getMessagePairCount());
        assertEquals("q3", restored.getHistory().get(0).get("content"));
    }

    @Test
    void deleteSession_shouldRemoveInMemoryAndPersistedHistory(@TempDir Path historyDir) {
        ChatHistoryStore store = newHistoryStore(historyDir);
        setField(sessionManager, "chatHistoryStore", store);
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("delete-test");
        session.addMessage("hello", "hi", sessionManager);

        assertTrue(store.load("delete-test").isPresent());

        sessionManager.deleteSession("delete-test");

        assertNull(sessionManager.getSession("delete-test"));
        assertTrue(store.load("delete-test").isEmpty());
    }

    private ChatHistoryStore newHistoryStore(Path historyDir) {
        AppChatHistoryProperties properties = new AppChatHistoryProperties();
        properties.setPath(historyDir.toString());
        return new ChatHistoryStore(properties, new ObjectMapper());
    }
}
