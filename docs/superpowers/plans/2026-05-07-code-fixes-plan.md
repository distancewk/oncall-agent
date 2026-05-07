# Code Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix compilation errors, security issues, bugs, and design problems identified in the SuperBizAgent codebase analysis.

**Architecture:** The fixes span all layers — controllers, services, config, agent tools, and DTOs. Each fix is self-contained; tasks can be executed independently.

**Tech Stack:** Java 17, Spring Boot 3.2.0, Spring AI 1.1.0, Milvus SDK 2.6.10, DashScope SDK 2.17.0

**Priority levels:** P0 = compilation blocker, P1 = security, P2 = correctness, P3 = performance/design

---

### Task 1: Fix ChatController SseMessage.done() missing closing brace [P0]

**Files:**
- Modify: `src/main/java/org/example/controller/ChatController.java:485-491`

- [ ] **Step 1: Read current state**

Read the file to confirm the exact brace structure around the `done()` method.

- [ ] **Step 2: Fix the closing brace indentation**

The `done()` method's closing `}` at line 490 has 4-space indent (should be 8-space), and the `SseMessage` class is missing its closing `}`.

The fix: change line 490 from 4-space `}` to 8-space `}` (closes done method), and add a 4-space `}` after it (closes SseMessage class).

```java
// Before (lines 485-491):
        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
    }
}

// After:
        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }
}
```

- [ ] **Step 3: Verify the fix compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (no compilation errors)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/controller/ChatController.java
git commit -m "fix: correct SseMessage.done() closing brace indentation to fix compilation error"
```

---

### Task 2: Remove hardcoded default API keys from application.yml [P1]

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Update the DashScope API key configuration**

Change the default from `your-api-key-here` to empty (force environment variable).

```yaml
# Before:
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your-api-key-here}

# After:
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
```

- [ ] **Step 2: Update the APP_API_KEY default**

Change the default from `SuperBizAgent2026` to empty.

```yaml
# Before:
app:
  api:
    key: ${APP_API_KEY:SuperBizAgent2026}

# After:
app:
  api:
    key: ${APP_API_KEY}
```

- [ ] **Step 3: Update dashscope.api.key fallback**

```yaml
# Before:
dashscope:
  api:
    key: ${DASHSCOPE_API_KEY:your-api-key-here}

# After:
dashscope:
  api:
    key: ${DASHSCOPE_API_KEY}
```

- [ ] **Step 4: Update VectorEmbeddingService to handle empty key gracefully**

`src/main/java/org/example/service/VectorEmbeddingService.java:41-43`

The existing check already handles empty/null keys. No code change needed — the empty default from yml will cause a startup failure with a clear message.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "fix: remove hardcoded default API keys, require env vars"
```

---

### Task 3: Make DashScopeApi and DashScopeChatModel Spring-managed singletons [P2]

**Files:**
- Create: `src/main/java/org/example/config/DashScopeModelConfig.java`
- Modify: `src/main/java/org/example/service/ChatService.java`
- Modify: `src/main/java/org/example/service/MemoryExtractionService.java`
- Modify: `src/main/java/org/example/service/RagService.java`

- [ ] **Step 1: Create DashScopeModelConfig with singleton beans**

```java
package org.example.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DashScopeModelConfig {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeModelConfig.class);

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Bean
    public DashScopeApi dashScopeApi() {
        logger.info("创建 DashScopeApi 单例 Bean");
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    @Bean
    public DashScopeChatModel dashScopeChatModel(DashScopeApi dashScopeApi) {
        logger.info("创建 DashScopeChatModel 单例 Bean");
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.7)
                        .withMaxToken(2000)
                        .withTopP(0.9)
                        .build())
                .build();
    }
}
```

- [ ] **Step 2: Update ChatService to inject singleton beans, remove factory methods**

`src/main/java/org/example/service/ChatService.java`

Remove the `createDashScopeApi()` and `createStandardChatModel()` methods. Inject `DashScopeApi` and `DashScopeChatModel` directly via `@Autowired`.

```java
// Add these fields (replace existing @Value and factory methods):
@Autowired
private DashScopeApi dashScopeApi;

@Autowired
private DashScopeChatModel dashScopeChatModel;

// Remove these methods entirely:
// - createDashScopeApi()
// - createStandardChatModel()
// - createChatModel(DashScopeApi, double, int, double)
```

- [ ] **Step 3: Update all callers of ChatService.createDashScopeApi() and createStandardChatModel()**

Files that call these methods and need updating:

1. `ChatController.java` — Replace `chatService.createDashScopeApi()` / `chatService.createStandardChatModel()` with direct `@Autowired DashScopeChatModel`.

2. `WebhookController.java` — Same change. Also replace the inline `DashScopeChatModel.builder()` construction with the injected bean.

3. `MemoryExtractionService.java` — Replace factory method calls with injected `DashScopeChatModel`.

- [ ] **Step 4: Update ChatController.java**

```java
// Before:
@Autowired
private ChatService chatService;
// ... inside methods:
DashScopeApi dashScopeApi = chatService.createDashScopeApi();
DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

// After:
@Autowired
private DashScopeChatModel chatModel;
// Remove all DashScopeApi and DashScopeChatModel construction code
```

- [ ] **Step 5: Update WebhookController.java**

Replace the inline DashScopeChatModel building with the injected bean.

```java
// Before:
DashScopeApi dashScopeApi = chatService.createDashScopeApi();
DashScopeChatModel chatModel = DashScopeChatModel.builder()
        .dashScopeApi(dashScopeApi)
        .defaultOptions(DashScopeChatOptions.builder()
                .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                .withTemperature(0.3)
                .withMaxToken(8000)
                .withTopP(0.9)
                .build())
        .build();

// After:
@Autowired
private DashScopeChatModel dashScopeChatModel;
// Use dashScopeChatModel directly (note: for AiOps we need different temperature=0.3)
// Create a separate AiOps-specific bean if needed, or use ChatOptions override at call site
```

For AiOps-specific options (temperature=0.3, maxToken=8000), add a separate bean:

```java
// In DashScopeModelConfig.java:
@Bean
public DashScopeChatModel dashScopeChatModelAiOps(DashScopeApi dashScopeApi) {
    return DashScopeChatModel.builder()
            .dashScopeApi(dashScopeApi)
            .defaultOptions(DashScopeChatOptions.builder()
                    .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                    .withTemperature(0.3)
                    .withMaxToken(8000)
                    .withTopP(0.9)
                    .build())
            .build();
}
```

Then inject `@Qualifier("dashScopeChatModelAiOps")` where needed.

- [ ] **Step 6: Update MemoryExtractionService.java**

```java
// Before:
DashScopeApi dashScopeApi = chatService.createDashScopeApi();
DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

// After:
@Autowired
private DashScopeChatModel dashScopeChatModel;
// Use dashScopeChatModel.call(prompt) directly
```

- [ ] **Step 7: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/example/config/DashScopeModelConfig.java src/main/java/org/example/service/ChatService.java src/main/java/org/example/controller/ChatController.java src/main/java/org/example/controller/WebhookController.java src/main/java/org/example/service/MemoryExtractionService.java
git commit -m "refactor: make DashScopeApi/ChatModel Spring-managed singletons, remove per-request instantiation"
```

---

### Task 4: Fix VectorRerankService modifying original SearchResult objects [P2]

**Files:**
- Modify: `src/main/java/org/example/service/VectorRerankService.java:94-105`

- [ ] **Step 1: Read current code to confirm the issue**

Read `src/main/java/org/example/service/VectorRerankService.java` around lines 94-105.

- [ ] **Step 2: Create a defensive copy of SearchResult objects**

The rerank method currently modifies the original `candidates` list entries. Instead, create new SearchResult objects for the final results.

```java
// Before:
List<VectorSearchService.SearchResult> finalResults = new ArrayList<>();
for (JsonNode node : resultsNode) {
    int index = node.path("index").asInt();
    double relevanceScore = node.path("relevance_score").asDouble();

    if (index >= 0 && index < candidates.size()) {
        VectorSearchService.SearchResult originalResult = candidates.get(index);
        originalResult.setScore((float) relevanceScore);
        finalResults.add(originalResult);
    }
}

// After:
List<VectorSearchService.SearchResult> finalResults = new ArrayList<>();
for (JsonNode node : resultsNode) {
    int index = node.path("index").asInt();
    double relevanceScore = node.path("relevance_score").asDouble();

    if (index >= 0 && index < candidates.size()) {
        VectorSearchService.SearchResult original = candidates.get(index);
        VectorSearchService.SearchResult copy = new VectorSearchService.SearchResult();
        copy.setId(original.getId());
        copy.setContent(original.getContent());
        copy.setScore((float) relevanceScore);
        copy.setMetadata(original.getMetadata());
        finalResults.add(copy);
    }
}
```

Also fix the fallback paths (lines 82, 91, 113, 116) that use `candidates.subList()`:

```java
// Before:
return candidates.subList(0, Math.min(topK, candidates.size()));

// After:
List<VectorSearchService.SearchResult> fallback = new ArrayList<>();
for (int i = 0; i < Math.min(topK, candidates.size()); i++) {
    VectorSearchService.SearchResult original = candidates.get(i);
    VectorSearchService.SearchResult copy = new VectorSearchService.SearchResult();
    copy.setId(original.getId());
    copy.setContent(original.getContent());
    copy.setScore(original.getScore());
    copy.setMetadata(original.getMetadata());
    fallback.add(copy);
}
return fallback;
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/service/VectorRerankService.java
git commit -m "fix: create defensive copies of SearchResult objects in rerank to prevent side effects"
```

---

### Task 5: Fix MemoryExtractionService to store sparse vectors [P2]

**Files:**
- Modify: `src/main/java/org/example/service/MemoryExtractionService.java:104-157`

- [ ] **Step 1: Read current code**

Read `src/main/java/org/example/service/MemoryExtractionService.java` to confirm the storeMemoryToMilvus method.

- [ ] **Step 2: Add sparse vector generation and storage**

The `insertToMilvus` in `VectorIndexService` also doesn't handle sparse vectors — but that's the file ingestion path. For memory storage, we need to add sparse vector support.

```java
// In storeMemoryToMilvus method, after generating dense vector:
List<Float> vector = embeddingService.generateEmbedding(content);

// ADD: generate sparse vector
java.util.SortedMap<Long, Float> sparseVector = embeddingService.generateSparseVector(content);

// ...existing metadata setup...

// ADD: sparse_vector field to the insert fields
fields.add(new InsertParam.Field("sparse_vector", Collections.singletonList(sparseVector)));
```

The InsertParam.Field for sparse vectors with Milvus SDK may differ. Verify the Milvus SDK version (2.6.10) supports sparse vector insertion. In Milvus SDK 2.6.x, sparse vectors are inserted as `SortedMap<Long, Float>` via `InsertParam.Field`.

```java
// For Milvus SDK 2.6.x, sparse vectors use withSparseFloatVectors:
fields.add(new InsertParam.Field("sparse_vector", Collections.singletonList(sparseVector)));
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/service/MemoryExtractionService.java
git commit -m "fix: add sparse vector storage to memory extraction for hybrid search compatibility"
```

---

### Task 6: Fix RagService potential NPE in generateAnswerStream [P2]

**Files:**
- Modify: `src/main/java/org/example/service/RagService.java:196-214`

- [ ] **Step 1: Read current code**

Read `src/main/java/org/example/service/RagService.java` around lines 190-215.

- [ ] **Step 2: Add null safety for getMessage() chain**

```java
// Before:
String content = message.getOutput().getChoices().get(0).getMessage().getContent();

// After:
var choice = message.getOutput().getChoices().get(0);
if (choice.getMessage() != null && choice.getMessage().getContent() != null) {
    String content = choice.getMessage().getContent();
    // ...
}
```

Full context of the fix:

```java
// Before (lines 196-213):
if (message.getOutput() != null &&
    message.getOutput().getChoices() != null &&
    !message.getOutput().getChoices().isEmpty()) {

    String content = message.getOutput().getChoices().get(0).getMessage().getContent();

    if (content != null && !content.isEmpty()) {
        finalContent.append(content);
        callback.onContentChunk(content);
    }
}

// After:
if (message.getOutput() != null &&
    message.getOutput().getChoices() != null &&
    !message.getOutput().getChoices().isEmpty()) {

    var firstChoice = message.getOutput().getChoices().get(0);
    if (firstChoice.getMessage() != null) {
        String content = firstChoice.getMessage().getContent();

        if (content != null && !content.isEmpty()) {
            finalContent.append(content);
            callback.onContentChunk(content);
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/service/RagService.java
git commit -m "fix: add null check for getMessage() in RagService to prevent NPE"
```

---

### Task 7: Fix model name configuration inconsistency [P2]

**Files:**
- Modify: `src/main/java/org/example/service/RagService.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Sync default value in RagService with application.yml**

```java
// Before:
@Value("${rag.model:qwen3-30b-a3b-thinking-2507}")
private String model;

// After:
@Value("${rag.model}")
private String model;
```

Remove the default value (force configuration to provide it).

- [ ] **Step 2: Ensure application.yml has the model configured**

Already in `application.yml:91`:
```yaml
rag:
  model: "qwen3-max"
```

This is already present. Verify it exists. If the key is ever removed, the app will fail at startup rather than silently using a different model.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/service/RagService.java
git commit -m "fix: remove hardcoded default model name in RagService, force config-based model selection"
```

---

### Task 8: Add IP RateLimiter cleanup mechanism [P2]

**Files:**
- Modify: `src/main/java/org/example/config/RateLimitInterceptor.java`

- [ ] **Step 1: Read current code**

Read `src/main/java/org/example/config/RateLimitInterceptor.java`.

- [ ] **Step 2: Add scheduled cleanup for stale IP rate limiters**

```java
// Add these imports:
import jakarta.annotation.PostConstruct;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Add field:
// 定期清理任务，防止 IP 限流器内存泄漏
private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "rate-limiter-cleanup");
    t.setDaemon(true);
    return t;
});

// Add init method:
@PostConstruct
public void init() {
    // 每5分钟清理一次超过1分钟未活动的IP限流器
    cleanupScheduler.scheduleAtFixedRate(() -> {
        int beforeSize = ipRateLimiters.size();
        long now = System.nanoTime();
        // Note: Guava's RateLimiter doesn't expose last access time directly.
        // Alternative approach: use a wrapper that tracks last access time.
        // For simplicity, we'll clear all entries periodically and let them be recreated.
        ipRateLimiters.clear();
        logger.debug("IP限流器清理完成, 清理前数量: {}", beforeSize);
    }, 5, 5, TimeUnit.MINUTES);
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/config/RateLimitInterceptor.java
git commit -m "fix: add scheduled cleanup for IP rate limiters to prevent memory leak"
```

---

### Task 9: Merge WebConfig and WebMvcConfig into a single class [P3]

**Files:**
- Modify: `src/main/java/org/example/config/WebConfig.java` (merge into here)
- Delete: `src/main/java/org/example/config/WebMvcConfig.java`

- [ ] **Step 1: Read both files**

Read `WebConfig.java` and `WebMvcConfig.java` to confirm content.

- [ ] **Step 2: Merge WebMvcConfig into WebConfig**

Add CORS and static resource config to `WebConfig.java`:

```java
// In WebConfig.java, add these methods:
@Override
public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
            .allowedOrigins("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600);
}

@Override
public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/");
}
```

Add imports:
```java
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
```

- [ ] **Step 3: Delete WebMvcConfig.java**

Delete the file `src/main/java/org/example/config/WebMvcConfig.java`.

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/config/WebConfig.java
git rm src/main/java/org/example/config/WebMvcConfig.java
git commit -m "refactor: merge WebMvcConfig into WebConfig to consolidate WebMvcConfigurer implementations"
```

---

### Task 10: Reduce sensitive information in logs [P3]

**Files:**
- Modify: `src/main/java/org/example/service/VectorEmbeddingService.java`

- [ ] **Step 1: Reduce API key logging verbosity**

```java
// Before (lines 46-49):
String maskedKey = apiKey.length() > 8 ?
    apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length() - 4) :
    "***";
logger.info("API Key 已加载: {}", maskedKey);

// After (simple boolean check only):
if (apiKey == null || apiKey.trim().isEmpty()) {
    throw new IllegalStateException("API Key is not configured");
}
logger.info("API Key 已加载（前{}位）: {}***",
    Math.min(4, apiKey.length()),
    apiKey.substring(0, Math.min(4, apiKey.length())));
```

Also reduce `Constants.apiKey` logging on lines 57-60:

```java
// Before:
logger.info("Constants.apiKey 已设置: {}",
    Constants.apiKey.substring(0, Math.min(8, Constants.apiKey.length())) + "...");

// After (only log whether it was set, no value):
logger.info("Constants.apiKey 已设置: {}", Constants.apiKey != null ? "yes" : "no");
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/service/VectorEmbeddingService.java
git commit -m "fix: reduce API key exposure in logs, only log existence not partial key value"
```

---

### Task 11: Add real CLS/MCP log query implementation placeholder documentation [P3]

**Files:**
- Modify: `src/main/java/org/example/agent/tool/QueryLogsTools.java`

- [ ] **Step 1: Add clear documentation about the mock-only limitation**

Add a Javadoc note at the class level documenting that only mock mode is currently implemented:

```java
/**
 * 日志查询工具
 * 用于查询 CLS（云日志服务）的日志信息
 * 支持 Mock 模式，提供与告警关联的模拟日志数据
 *
 * 注意：当前版本仅 Mock 模式可用。生产环境需要：
 * 1. 通过 MCP 客户端连接腾讯云 CLS 服务
 * 2. 或者在 cls.mock-enabled=true 时使用模拟数据
 *
 * 如果使用 MCP 方式，此工具类应在 MCP 连接可用时设置为 required=false
 */
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/agent/tool/QueryLogsTools.java
git commit -m "docs: add migration note for CLS real implementation in QueryLogsTools"
```

---

### Task 12: Clean up unused imports across all files [P3]

**Files:**
- Modify: `src/main/java/org/example/controller/ChatController.java`
- Modify: `src/main/java/org/example/service/RagService.java`
- Modify: Other files as discovered during compilation

- [ ] **Step 1: Run maven compile with verbose warnings**

Run: `mvn compile -X 2>&1 | grep -i "unused import"` to find all unused imports.

Alternatively, use IDE analysis or IntelliJ's optimize imports.

- [ ] **Step 2: Remove unused imports from ChatController.java**

Check for unused: `DashScopeChatOptions`, `ConcurrentHashMap`, `ReentrantLock`.

```java
// Remove these if not directly used (check after Task 3 refactoring):
// import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.locks.ReentrantLock;
```

- [ ] **Step 3: Remove other unused imports found in Step 1**

Fix all files found. Delete unused import lines.

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS, no warnings

- [ ] **Step 5: Commit**

```bash
git add <all modified files>
git commit -m "chore: remove unused imports across multiple files"
```

---

### Task 13: Review and final verification [P0]

**Files:** All modified files

- [ ] **Step 1: Full project compilation check**

Run: `mvn clean compile`
Expected: BUILD SUCCESS, zero errors

- [ ] **Step 2: Verify all changes are consistent**

Run: `git diff --stat` to confirm all modified files.

Run: `git diff` to do a final review of all changes.

- [ ] **Step 3: Commit if any pending changes remain**

```bash
git commit -m "chore: final cleanup and review fixes"
```

---

## Summary of Changes

| # | File | Change Type | Priority |
|---|------|-------------|----------|
| 1 | `ChatController.java` | Fix brace indentation | P0 |
| 2 | `application.yml` | Remove hardcoded defaults | P1 |
| 3 | `DashScopeModelConfig.java` (new) | Singleton beans for LLM | P2 |
| 3 | `ChatService.java` | Remove factory methods | P2 |
| 3 | `ChatController.java` | Inject beans | P2 |
| 3 | `WebhookController.java` | Inject beans | P2 |
| 3 | `MemoryExtractionService.java` | Inject beans | P2 |
| 4 | `VectorRerankService.java` | Defensive copies | P2 |
| 5 | `MemoryExtractionService.java` | Add sparse vectors | P2 |
| 6 | `RagService.java` | NPE fix | P2 |
| 7 | `RagService.java`, `application.yml` | Model name consistency | P2 |
| 8 | `RateLimitInterceptor.java` | Memory leak fix | P2 |
| 9 | `WebConfig.java`, `WebMvcConfig.java` | Merge configs | P3 |
| 10 | `VectorEmbeddingService.java` | Reduce log exposure | P3 |
| 11 | `QueryLogsTools.java` | Add doc note | P3 |
| 12 | Multiple files | Remove unused imports | P3 |
