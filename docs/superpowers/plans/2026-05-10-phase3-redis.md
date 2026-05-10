# Phase 3: Redis Session Migration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate SessionManager from in-memory ConcurrentHashMap to Redis-backed persistent storage, keeping the public interface identical.

**Architecture:** Keep ConcurrentHashMap as L1 cache for performance, add Redis as persistence layer. On session mutations, write through to both. On reads, check L1 first then Redis. Add TTL-based expiry (1 hour, refreshed on access).

**Tech Stack:** Spring Boot Starter Data Redis, Lettuce, Jackson, Docker Compose

---

### Task 1: Add Redis Dependency to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add spring-boot-starter-data-redis**

Add to `<dependencies>` after `spring-boot-starter-test`:

```xml
        <!-- Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
```

- [ ] **Step 2: Verify dependency resolves**

Run: `mvn dependency:resolve -B -q`
Expected: No errors.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: add spring-boot-starter-data-redis dependency"
```

---

### Task 2: Add Redis Configuration

**Files:**
- Create: `src/main/java/org/example/config/RedisConfig.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add Redis connection config to application.yml**

Append to `src/main/resources/application.yml`:

```yaml
spring:
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    timeout: 3000
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
```

Note: The `spring.redis` block must be merged at the correct indentation level. Since there's already a `spring:` block with `ai:` and `mcp:` children, add `redis:` as a sibling to `ai:`.

Read the current file carefully to find the right insertion point. The existing `spring:` block looks like:
```yaml
spring:
  ai:
    dashscope:
      ...
    mcp:
      client.enabled: false
```

Add `redis:` at the same level as `ai:` inside the `spring:` block:

```yaml
spring:
  ai:
    ...
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    timeout: 3000
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
  mcp:
    client.enabled: false
```

- [ ] **Step 2: Create RedisConfig.java**

Create `src/main/java/org/example/config/RedisConfig.java`:

```java
package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 * 配置 StringRedisTemplate 用于会话持久化存储
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/config/RedisConfig.java src/main/resources/application.yml
git commit -m "feat: add Redis configuration with StringRedisTemplate"
```

---

### Task 3: Add Redis Service to Docker Compose

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add Redis service**

Add to `docker-compose.yml` after the `attu` service:

```yaml
  redis:
    image: redis:7-alpine
    container_name: session-redis
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5
    networks:
      - app-network
```

Also add `REDIS_HOST=redis` to the `app` service environment:

```yaml
    environment:
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY}
      MILVUS_HOST: milvus
      MILVUS_PORT: 19530
      REDIS_HOST: redis
      REDIS_PORT: 6379
```

And add `redis` to the `app` service's `depends_on`:
```yaml
    depends_on:
      milvus:
        condition: service_started
      redis:
        condition: service_started
```

- [ ] **Step 2: Verify docker-compose can parse**

Run: `docker compose config`
Expected: Parses successfully.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add Redis service to docker-compose.yml"
```

---

### Task 4: Modify SessionManager for Redis Persistence

**Files:**
- Modify: `src/main/java/org/example/service/SessionManager.java`
- Modify: `src/main/java/org/example/service/SessionManager.java` (SessionInfo inner class)

**Design:**
- Keep ConcurrentHashMap as L1 cache
- On `getOrCreateSession`: check L1, then Redis, create new if neither
- On `getSession`: check L1, then Redis
- After `addMessage` / `clearHistory`, persist to Redis
- Use Jackson ObjectMapper to serialize/deserialize SessionInfo data
- TTL: 1 hour, refreshed on each access

- [ ] **Step 1: Modify SessionManager class**

Read the current file. Make these changes:

**New imports to add:**
```java
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
```

**New fields to add (alongside existing autowired fields):**
```java
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    private static final String REDIS_KEY_PREFIX = "session:";
    private static final long SESSION_TTL_SECONDS = 3600; // 1 hour
```

**Modify `getOrCreateSession` method:**

Replace the current implementation:
```java
    public SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        return sessions.computeIfAbsent(sessionId, SessionInfo::new);
    }
```

With:
```java
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
        // Create new
        SessionInfo session = new SessionInfo(sessionId);
        sessions.put(sessionId, session);
        saveToRedis(session);
        return session;
    }
```

**Modify `getSession` method:**

Replace:
```java
    public SessionInfo getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        return sessions.get(sessionId);
    }
```

With:
```java
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
        return null;
    }
```

**Add private helper methods:**

```java
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
            SessionInfo session = new SessionInfo(sessionId);

            Object createTimeVal = data.get("createTime");
            if (createTimeVal instanceof Number) {
                java.lang.reflect.Field createTimeField = SessionInfo.class.getDeclaredField("createTime");
                createTimeField.setAccessible(true);
                createTimeField.set(session, ((Number) createTimeVal).longValue());
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> history = (List<Map<String, String>>) data.get("messageHistory");
            if (history != null) {
                java.lang.reflect.Field historyField = SessionInfo.class.getDeclaredField("messageHistory");
                historyField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Map<String, String>> mutableHistory = new java.util.ArrayList<>();
                for (Map<String, String> entry : history) {
                    mutableHistory.add(new java.util.HashMap<>(entry));
                }
                historyField.set(session, mutableHistory);
            }

            return session;
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
```

**Add a `saveSession` method (called by SessionInfo after mutations):**

```java
    /**
     * Persist session changes to Redis. Called by SessionInfo after mutations.
     */
    public void saveSession(SessionInfo session) {
        saveToRedis(session);
    }
```

- [ ] **Step 2: Modify SessionInfo.clearHistory() to persist**

Update `clearHistory()` to call back to SessionManager:

Old:
```java
        public void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
            } finally {
                lock.unlock();
            }
        }
```

New:
```java
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
        public void clearHistory(SessionManager manager) {
            lock.lock();
            try {
                messageHistory.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
                if (manager != null) {
                    manager.saveSession(this);
                }
            } finally {
                lock.unlock();
            }
        }
```

Note: The original `clearHistory()` is kept with its original signature for backward compatibility (no persistence). An overloaded `clearHistory(SessionManager manager)` is added for the Redis-persist path.

- [ ] **Step 3: Update ChatController to persist after clearHistory**

In `ChatController.java`, endpoint `POST /api/chat/clear`, change:
```java
session.clearHistory();
```
To:
```java
session.clearHistory(sessionManager);
```

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/service/SessionManager.java src/main/java/org/example/controller/ChatController.java
git commit -m "feat: add Redis persistence to SessionManager with L1 cache"
```

---

### Task 5: Update SessionManagerTest for Redis Changes

**Files:**
- Modify: `src/test/java/org/example/service/SessionManagerTest.java`

- [ ] **Step 1: Update test setup**

The `SessionManager` now has optional `StringRedisTemplate` and `ObjectMapper` fields. When these are null (as they will be in the existing test since we don't mock them), the Redis persistence is skipped and the in-memory-only path is used. This means the existing tests should still pass without modification.

Read the current `SessionManagerTest.java` and verify it compiles and runs against the modified `SessionManager`.

- [ ] **Step 2: Run tests to verify backward compatibility**

Run: `mvn test -Dtest=SessionManagerTest -DfailIfNoTests=false`
Expected: All existing SessionManager tests pass (Redis path returns early since redisTemplate is null).

- [ ] **Step 3: Run full test suite**

Run: `mvn test -DfailIfNoTests=false`
Expected: All 27 existing tests pass + any new tests.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: verify SessionManager Redis changes are backward compatible"
```

---

### Task 6: Run Full Build Verification

- [ ] **Step 1: Run full verify lifecycle**

Run: `mvn clean verify -B`
Expected: BUILD SUCCESS (all tests pass).

---

## Self-Review Checklist

1. **Spec coverage:** All items from improvement-plan item #6 covered: Redis dependency, config, SessionManager migration, backward-compatible interface, TTL expiry.
2. **Backward compatibility:** SessionManager's public interface is unchanged (`getOrCreateSession`, `getSession` signatures are identical). SessionInfo's original `clearHistory()` is preserved.
3. **Graceful degradation:** When Redis is not available (redisTemplate is null), SessionManager falls back to in-memory-only mode.
4. **TTL:** 1-hour expiry, refreshed on every access via `refreshRedisTtl`.
5. **Docker Compose:** Redis service added with healthcheck, app service gets REDIS_HOST/REDIS_PORT env vars.
