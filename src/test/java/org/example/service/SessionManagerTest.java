package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppChatHistoryProperties;
import org.example.config.AppMemoryProperties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    private SessionManager sessionManager;

    @Mock
    private MemoryExtractionService memoryExtractionService;

    @Mock
    private java.util.concurrent.Executor executor;

    @Mock
    private Executor memoryExecutor;

    @Mock
    private MemoryLifecycleService memoryLifecycleService;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager();
        setField(sessionManager, "memoryExtractionService", memoryExtractionService);
        setField(sessionManager, "executor", executor);
        setField(sessionManager, "memoryExecutor", memoryExecutor);
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
        setField(restarted, "memoryExecutor", memoryExecutor);
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

    @Test
    void clearHistory_shouldDeletePrivateMemoriesForSession() {
        setField(sessionManager, "memoryLifecycleService", memoryLifecycleService);
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("session-1");

        assertTrue(session.clearHistory(sessionManager));

        verify(memoryLifecycleService).deleteSessionMemories("session-1");
    }

    @Test
    void clearHistory_shouldReportPrivateMemoryDeletionFailure() {
        setField(sessionManager, "memoryLifecycleService", memoryLifecycleService);
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("session-1");
        doThrow(new RuntimeException("milvus unavailable"))
                .when(memoryLifecycleService).deleteSessionMemories("session-1");

        assertFalse(session.clearHistory(sessionManager));

        verify(memoryLifecycleService, times(2)).deleteSessionMemories("session-1");
    }

    @Test
    void deleteSession_shouldDeletePrivateMemoriesForSession() {
        setField(sessionManager, "memoryLifecycleService", memoryLifecycleService);
        sessionManager.getOrCreateSession("session-1");

        assertTrue(sessionManager.deleteSession("session-1"));

        verify(memoryLifecycleService).deleteSessionMemories("session-1");
    }

    @Test
    void deleteSession_shouldStillRemoveSessionWhenPrivateMemoryDeletionFails() {
        setField(sessionManager, "memoryLifecycleService", memoryLifecycleService);
        sessionManager.getOrCreateSession("session-1");
        doThrow(new RuntimeException("milvus unavailable"))
                .when(memoryLifecycleService).deleteSessionMemories("session-1");

        SessionManager.SessionDeletionResult result = sessionManager.deleteSessionWithStatus("session-1");

        assertTrue(result.deleted());
        assertFalse(result.privateMemoryDeleted());
        assertNull(sessionManager.getSession("session-1"));
        verify(memoryLifecycleService, times(2)).deleteSessionMemories("session-1");
    }

    @Test
    void triggerMemoryExtraction_shouldUseMemoryExecutor() {
        SessionManager manager = new SessionManager();
        setField(manager, "memoryExtractionService", memoryExtractionService);
        setField(manager, "memoryExecutor", memoryExecutor);

        manager.triggerMemoryExtraction(
                "session-1",
                List.of(Map.of("role", "user", "content", "偏好 Java")));

        verify(memoryExecutor).execute(any(Runnable.class));
    }

    @Test
    void clearHistory_shouldCancelQueuedMemoryExtractionForSession() {
        List<Runnable> queuedTasks = new ArrayList<>();
        setField(sessionManager, "memoryExecutor", (Executor) queuedTasks::add);
        setField(sessionManager, "memoryLifecycleService", memoryLifecycleService);
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("session-1");

        sessionManager.triggerMemoryExtraction(
                "session-1",
                List.of(Map.of("role", "user", "content", "偏好 Java")));
        assertTrue(session.clearHistory(sessionManager));
        queuedTasks.get(0).run();

        verify(memoryExtractionService, never()).extractAndStore(any(), any());
        verify(memoryLifecycleService).deleteSessionMemories("session-1");
    }

    @Test
    void deletedSession_shouldCancelExtractionTriggeredByStaleSessionObject() {
        setField(sessionManager, "memoryExecutor", (Executor) Runnable::run);
        setField(sessionManager, "memoryLifecycleService", memoryLifecycleService);
        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("session-1");
        for (int i = 1; i <= 6; i++) {
            session.addMessage("q" + i, "a" + i, sessionManager);
        }

        SessionManager.SessionDeletionResult result = sessionManager.deleteSessionWithStatus("session-1");
        session.addMessage("q7", "a7", sessionManager);

        assertTrue(result.deleted());
        assertTrue(result.privateMemoryDeleted());
        verify(memoryExtractionService, never()).extractAndStore(any(), any());
    }

    @Test
    void evictedMessages_shouldCoalesceIntoOneBoundedBatch() {
        AppMemoryProperties properties = new AppMemoryProperties();
        properties.setExtractionBatchSize(6);
        setField(sessionManager, "appMemoryProperties", properties);

        ScheduledExecutorService scheduler = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        List<Runnable> scheduledTasks = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    scheduledTasks.add(invocation.getArgument(0));
                    return org.mockito.Mockito.mock(ScheduledFuture.class);
                });
        setField(sessionManager, "memoryFlushScheduler", scheduler);
        setField(sessionManager, "memoryExecutor", (Executor) Runnable::run);

        SessionManager.SessionInfo session = sessionManager.getOrCreateSession("evict-batch");
        for (int i = 1; i <= 8; i++) {
            session.addMessage("q" + i, "a" + i, sessionManager);
        }

        verify(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        assertEquals(1, scheduledTasks.size());
        scheduledTasks.get(0).run();

        verify(memoryExtractionService).extractAndStoreBatch(eq("evict-batch"),
                org.mockito.ArgumentMatchers.argThat(messages -> messages.size() == 4));
    }

    private ChatHistoryStore newHistoryStore(Path historyDir) {
        AppChatHistoryProperties properties = new AppChatHistoryProperties();
        properties.setPath(historyDir.toString());
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:" + historyDir.resolve("chat-db"));
        dataSource.setUsername("");
        dataSource.setPassword("");
        return new ChatHistoryStore(
                properties, new ObjectMapper(), dataSource, new IncidentSchemaMigrator(dataSource));
    }
}
