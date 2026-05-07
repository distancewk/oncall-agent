# Improvement Plan

> Areas for future optimization and enhancement of OnCall Agent.

## Priority: High

### 1. Add Unit & Integration Tests

- **Problem**: Zero test coverage (`src/test/` is empty). No unit tests, integration tests, or end-to-end tests exist.
- **Action**: Add JUnit 5 + Mockito for service-layer unit tests; add Spring Boot `@WebMvcTest` for controllers; add `@SpringBootTest` integration tests for critical flows (RAG, AIOps pipeline).
- **Key targets**: `ChatController`, `AiOpsService`, `VectorSearchService`, `SessionManager`, `RagService`.

### 2. Set Up CI/CD Pipeline

- **Problem**: No CI configuration (no `.github/workflows/` or Jenkinsfile).
- **Action**: Add a GitHub Actions workflow that runs `mvn verify` on every PR and push to `main`, including:
  - Build & compile check
  - Unit & integration tests
  - Code quality checks (see item 4)
  - Optional: Docker image build and push.

### 3. Add Dockerfile for the Application Itself

- **Problem**: Only `vector-database.yml` exists for Milvus. The application itself has no `Dockerfile`, so deployment requires manual `mvn spring-boot:run`.
- **Action**: Create a multi-stage `Dockerfile` that builds the JAR and runs it. Add a `docker-compose.yml` that combines the app + Milvus so the whole system starts with one command.

### 4. Improve Code Quality Tooling

- **Problem**: `pom.xml` has no static analysis plugins.
- **Action**: Add SpotBugs (or ErrorProne), Checkstyle, and PMD to the Maven build. Fail the build on violations to enforce code quality.
- **Specific issues to fix**:
  - **Code duplication**: `buildMethodToolsArray()` in `ChatService` and `AiOpsService` are identical — extract to a shared utility.
  - **Hardcoded URL**: `"https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"` in `VectorRerankService` should be configurable.
  - **Hardcoded URL**: `"https://dashscope.aliyuncs.com/api/v1"` in `RagService.init()` should be configurable.
  - **Magic strings**: System prompt text in `ChatService.buildSystemPrompt()` and `AiOpsService` should be externalized to resource files.

### 5. Secure Sensitive Information in Logs

- **Problem**: API keys and sensitive data may appear in logs. `VectorEmbeddingService` logs `apiKey.length()` and checks `"your-api-key-here"` literal — both reveal key metadata.
- **Action**: Sanitize all sensitive fields in `toString()`, log messages, and exception messages. Never log API key length or partial values.

### 6. Migrate Session Storage to Redis

- **Problem**: `SessionManager` stores sessions in `ConcurrentHashMap` (in-memory). Sessions are lost on restart; not suitable for multi-instance deployments. The code even has a comment saying "future: migrate to Redis".
- **Action**: Integrate Spring Data Redis. Use `RedisTemplate` or `StringRedisTemplate` with TTL-based expiry. Keep `SessionManager` interface the same so the rest of the code doesn't need changes.

## Priority: Medium

### 7. Add OpenAPI / Swagger Documentation

- **Problem**: All API endpoints exist but there is no Swagger/OpenAPI spec. Consumers must read the README or source to understand request/response formats.
- **Action**: Add `springdoc-openapi-starter-webmvc-ui` dependency, annotate controllers with `@Operation` and `@Schema`, expose at `/swagger-ui.html`.

### 8. Add Observability (Metrics & Health)

- **Problem**: Only a basic `/milvus/health` endpoint exists. No metrics, no distributed tracing.
- **Action**:
  - Add `spring-boot-starter-actuator` for `/actuator/health`, `/actuator/metrics`, `/actuator/info`.
  - Add Micrometer metrics for: request latency (by endpoint), AI model call latency, Milvus query latency, error rate, active sessions, thread pool utilization.
  - Optionally integrate with Prometheus (`micrometer-registry-prometheus`) for scraping.

### 9. Graceful Shutdown & Connection Draining

- **Problem**: No graceful shutdown handling. In-flight SSE connections and async tasks may be abruptly terminated when the app stops.
- **Action**: Register a Spring `@PreDestroy` or `SmartLifecycle` bean that:
  - Completes in-progress SSE emissions gracefully.
  - Shuts down the `chatTaskExecutor` thread pool with a grace period.
  - Closes the Milvus client connection.

### 10. Improve Sparse Vector Generation

- **Problem**: `VectorEmbeddingService.generateSparseVector()` uses a naive character-level hash with collisions (`% 1000000`), not a real tokenizer or BM25 model. This severely limits hybrid search quality.
- **Action**: Integrate a proper tokenizer (e.g., HanLP for Chinese, or a learned sparse encoder like Splade via DashScope). Or configure Milvus to use the built-in BM25 function (available in Milvus 2.4+).

### 11. Externalize AI Agent Prompts

- **Problem**: Multi-line system prompts for Planner, Executor, Supervisor agents are hardcoded in `AiOpsService.java` as Java text blocks. Any prompt change requires recompilation.
- **Action**: Move prompt templates to `src/main/resources/prompts/` as separate files (e.g., `planner-prompt.txt`, `executor-prompt.txt`, `supervisor-prompt.txt`). Load them via `ResourceLoader` or `@Value`.

## Priority: Low

### 12. Add Retry with Backoff for External API Calls

- **Problem**: Calls to DashScope (embedding, rerank, chat) and Milvus have no retry logic. Transient network failures cause immediate errors.
- **Action**: Use Spring Retry (`@Retryable`) or a resilience library like Resilience4j with exponential backoff for all external API calls.

### 13. Add Request Rate Limiting Configuration

- **Problem**: Rate limits (5 req/s per IP, 2 req/s for heavy APIs) are hardcoded in `RateLimitInterceptor`. They should be configurable via `application.yml`.
- **Action**: Create a `@ConfigurationProperties` class for rate limit settings (per-IP rate, heavy-API rate, cleanup interval).

### 14. Replace JAX-RS `@QueryParam` with Spring Equivalent

- **Problem**: The codebase mixes JAX-RS and Spring annotations in some places. Unify to Spring MVC annotations throughout.

### 15. Add CORS Whitelist Configuration

- **Problem**: `WebConfig.addCorsMappings()` uses `allowedOrigins("*")` which is overly permissive. In production, this should be restricted to known origins.
- **Action**: Make `allowedOrigins` configurable via `application.yml` with a sensible default.

### 16. Add Input Validation with Jakarta Validation

- **Problem**: Request validation is done manually (e.g., null checks in controller methods). No use of `@Valid`, `@NotBlank`, etc.
- **Action**: Add `jakarta.validation` constraints (`@NotBlank`, `@Size`) to request DTOs and use `@Valid` in controller parameters.

### 17. Add Log Aggregation Configuration

- **Problem**: Logs go to stdout and `server.log` (via Makefile's `nohup` redirect). No structured logging, no log rotation.
- **Action**: Configure Logback with a rolling file appender and JSON format (for ELK/Loki ingestion). Consider `logstash-logback-encoder`.

### 18. Document Architecture Decisions (ADR)

- **Problem**: Key architectural decisions (why Planner-Executor-Replanner pattern, why Milvus, why DashScope) are not documented.
- **Action**: Create an `adr/` directory with lightweight Architecture Decision Records for future reference.
