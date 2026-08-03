package org.example.service;

import org.slf4j.MDC;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived registry linking internal RAG result IDs to the current request.
 * W3C trace ID is preferred so agent tool execution can cross an executor
 * boundary; thread ID is only a test/local fallback when no request context
 * exists. Raw document content is never stored here.
 */
public final class RagCitationRegistry {

    private static final long MAX_AGE_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_IDS = 64;
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private RagCitationRegistry() {
    }

    public static void recordIds(Collection<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        purgeExpired();
        String key = currentKey();
        Entry entry = ENTRIES.computeIfAbsent(key, ignored -> new Entry());
        synchronized (entry) {
            sourceIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .limit(Math.max(0, MAX_IDS - entry.ids.size()))
                    .forEach(entry.ids::add);
        }
    }

    public static Set<String> consumeCurrent() {
        Entry entry = ENTRIES.remove(currentKey());
        if (entry == null) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(entry.ids));
    }

    public static void clearCurrent() {
        ENTRIES.remove(currentKey());
    }

    private static String currentKey() {
        String traceId = MDC.get("trace_id");
        return traceId == null || traceId.isBlank()
                ? "thread:" + Thread.currentThread().getId()
                : "trace:" + traceId;
    }

    private static void purgeExpired() {
        long cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS;
        ENTRIES.entrySet().removeIf(entry -> entry.getValue().createdAt < cutoff);
    }

    private static final class Entry {
        private final long createdAt = System.currentTimeMillis();
        private final Set<String> ids = ConcurrentHashMap.newKeySet();
    }
}
