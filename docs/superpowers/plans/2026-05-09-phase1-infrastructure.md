# Phase 1: Infrastructure Improvement Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build foundational infrastructure — Docker packaging, CI/CD pipeline, and code quality tooling with static analysis plugins.

**Architecture:** Three independent workstreams: (1) Multi-stage Dockerfile packaging the Spring Boot app + combined docker-compose.yml with Milvus, (2) GitHub Actions workflow for CI, (3) Maven static analysis plugins (SpotBugs, Checkstyle, PMD) with fixes for identified code quality issues (duplicated code, hardcoded URLs, magic strings).

**Tech Stack:** Docker, Docker Compose, GitHub Actions, SpotBugs, Checkstyle, PMD, Maven

---

### Task 1: Application Dockerfile

**Files:**
- Create: `Dockerfile`
- Modify: `.gitignore` (add Docker-specific ignores if missing)
- Reference: `pom.xml` (already configured with spring-boot-maven-plugin)

- [ ] **Step 1: Create multi-stage Dockerfile**

Create `Dockerfile` in the project root:

```dockerfile
# ---- Build Stage ----
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and pom.xml first for dependency caching
COPY pom.xml ./
COPY src ./src

# Build the application JAR (skip tests since there are none yet)
RUN apk add --no-cache maven && mvn package -DskipTests -B -q

# ---- Runtime Stage ----
FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy JAR from builder stage
COPY --from=builder /build/target/*.jar app.jar

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 9900

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:9900/milvus/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Verify Dockerfile builds**

Run: `docker build -t super-biz-agent:latest .`
Expected: Build succeeds, JAR is packaged and copied to runtime stage.

---

### Task 2: Combined Docker Compose (App + Milvus)

**Files:**
- Create: `docker-compose.yml`
- Reference: `vector-database.yml` (existing Milvus compose file)

- [ ] **Step 1: Create docker-compose.yml**

Create `docker-compose.yml` in the project root that extends the existing Milvus services and adds the application:

```yaml
version: '3.8'

services:
  etcd:
    image: quay.io/coreos/etcd:v3.5.18
    container_name: milvus-etcd
    command: etcd -advertise-client-urls=http://127.0.0.1:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd
    volumes:
      - ./volumes/etcd:/etcd
    networks:
      - app-network

  minio:
    image: minio/minio:RELEASE.2023-03-20T20-16-18Z
    container_name: milvus-minio
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    command: minio server /minio --console-address :9001
    volumes:
      - ./volumes/minio:/minio
    ports:
      - "9000:9000"
      - "9001:9001"
    networks:
      - app-network

  milvus:
    image: milvusdb/milvus:v2.5.10
    container_name: milvus-standalone
    command: milvus run standalone
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
    volumes:
      - ./volumes/milvus:/var/lib/milvus
    ports:
      - "19530:19530"
      - "9091:9091"
    depends_on:
      - etcd
      - minio
    networks:
      - app-network

  attu:
    image: zilliz/attu:v2.5
    container_name: milvus-attu
    ports:
      - "8000:8000"
    environment:
      MILVUS_URL: milvus:19530
    depends_on:
      - milvus
    networks:
      - app-network

  app:
    build: .
    container_name: super-biz-agent
    ports:
      - "9900:9900"
    environment:
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY}
      MILVUS_HOST: milvus
      MILVUS_PORT: 19530
    depends_on:
      milvus:
        condition: service_started
    networks:
      - app-network

networks:
  app-network:
    driver: bridge
```

- [ ] **Step 2: Verify docker-compose can parse**

Run: `docker compose config`
Expected: Outputs the resolved compose config without errors.

---

### Task 3: Update Makefile with Docker Compose Targets

**Files:**
- Modify: `Makefile`

- [ ] **Step 1: Update `up` target to use new docker-compose.yml**

Read the current `Makefile` at `/Users/distancewk/Downloads/SuperBizAgent-release-2026-01-02/Makefile` and update `DOCKER_COMPOSE_FILE` variable:

In the Makefile, change `DOCKER_COMPOSE_FILE=vector-database.yml` to `DOCKER_COMPOSE_FILE=docker-compose.yml` (or add a separate `DOCKER_COMPOSE_FILE_APP` variable for the new file).

Search for the line `DOCKER_COMPOSE_FILE=vector-database.yml` and change it, then add a Docker build target:

Makefile changes:
- Change `DOCKER_COMPOSE_FILE=vector-database.yml` to `DOCKER_COMPOSE_FILE=docker-compose.yml`
- Add a `docker-build` target if not present: `docker-build:\n\t$(DOCKER) build -t super-biz-agent:latest .`

---

### Task 4: CI/CD Pipeline — GitHub Actions

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Create GitHub Actions workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [ "main" ]
  pull_request:
    branches: [ "main" ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Build and analyze
        run: mvn clean verify -B
        env:
          DASHSCOPE_API_KEY: ${{ secrets.DASHSCOPE_API_KEY }}
```

Note: The workflow runs `mvn clean verify` which will execute unit/integration tests and static analysis once those are added in later phases.

- [ ] **Step 2: Create .github directory if needed**

Run: `mkdir -p /Users/distancewk/Downloads/SuperBizAgent-release-2026-01-02/.github/workflows`

---

### Task 5: Code Quality — Extract Duplicated Code

**Files:**
- Create: `src/main/java/org/example/util/ToolUtils.java`
- Modify: `src/main/java/org/example/service/ChatService.java` (replace `buildMethodToolsArray()` with call to utility)
- Modify: `src/main/java/org/example/service/AiOpsService.java` (replace `buildMethodToolsArray()` with call to utility)

**Problem:** `ChatService.buildMethodToolsArray()` and `AiOpsService.buildMethodToolsArray()` are identical — both create tool arrays with the same 4 tools conditionally based on `queryLogsTools` null check.

- [ ] **Step 1: Create shared ToolUtils utility class**

Create `src/main/java/org/example/util/ToolUtils.java`:

```java
package org.example.util;

import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;

/**
 * Shared utility for building agent tool arrays.
 * Extracted to eliminate duplication between ChatService and AiOpsService.
 */
public class ToolUtils {

    /**
     * Build method tools array, conditionally including QueryLogsTools.
     * When queryLogsTools is null (real mode), log querying is handled by MCP;
     * when present (mock mode), the local QueryLogsTools bean is included.
     */
    public static Object[] buildMethodToolsArray(
            DateTimeTools dateTimeTools,
            InternalDocsTools internalDocsTools,
            QueryMetricsTools queryMetricsTools,
            QueryLogsTools queryLogsTools) {
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }
}
```

- [ ] **Step 2: Update ChatService.buildMethodToolsArray()**

In `ChatService.java`, replace the entire `buildMethodToolsArray()` method body with a delegation:

Old code (lines 86-94):
```java
    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock mode: include QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // Real mode: exclude QueryLogsTools (MCP provides log querying)
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }
```

New code:
```java
    public Object[] buildMethodToolsArray() {
        return ToolUtils.buildMethodToolsArray(dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools);
    }
```

- [ ] **Step 3: Update AiOpsService.buildMethodToolsArray()**

In `AiOpsService.java`, replace the private `buildMethodToolsArray()` method body:

Old code (lines 131-139):
```java
    private Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock mode: include QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // Real mode: exclude QueryLogsTools (MCP provides log querying)
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }
```

New code:
```java
    private Object[] buildMethodToolsArray() {
        return ToolUtils.buildMethodToolsArray(dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools);
    }
```

---

### Task 6: Code Quality — Externalize Hardcoded URLs to Configuration

**Files:**
- Modify: `src/main/resources/application.yml` (add dashscope base-url config)
- Modify: `src/main/java/org/example/service/VectorRerankService.java` (use configurable URL)
- Modify: `src/main/java/org/example/service/RagService.java` (use configurable URL)

**Problem:** Two hardcoded DashScope URLs:
1. `VectorRerankService.java:71` — `"https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"`
2. `RagService.java:51` — `"https://dashscope.aliyuncs.com/api/v1"`

- [ ] **Step 1: Add configuration properties to application.yml**

Add to `application.yml` under `dashscope` section:

```yaml
dashscope:
  api.key: ${DASHSCOPE_API_KEY}
  base-url: https://dashscope.aliyuncs.com/api/v1
  rerank:
    url: https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
  embedding.model: text-embedding-v4
  rerank.model: gte-rerank
```

- [ ] **Step 2: Update VectorRerankService.java**

Add a `@Value` field for the rerank URL:

```java
@Value("${dashscope.rerank.url}")
private String rerankUrl;
```

Then replace line 71:
Old:
```java
.url("https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank")
```
New:
```java
.url(rerankUrl)
```

- [ ] **Step 3: Update RagService.java**

Add a `@Value` field for the base URL:

```java
@Value("${dashscope.base-url}")
private String baseUrl;
```

Then replace line 51:
Old:
```java
Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
```
New:
```java
Constants.baseHttpApiUrl = baseUrl;
```

---

### Task 7: Code Quality — Add Static Analysis Plugins to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add SpotBugs, Checkstyle, and PMD plugins to `<build><plugins>`**

Add these plugins inside `<build><plugins>` after the `maven-compiler-plugin`:

```xml
            <!-- SpotBugs: Find Bugs -->
            <plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>4.8.6</version>
                <configuration>
                    <excludeFilterFile>spotbugs-exclude.xml</excludeFilterFile>
                    <effort>Max</effort>
                    <threshold>Low</threshold>
                    <failOnError>true</failOnError>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>check</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>

            <!-- Checkstyle: Code Style -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <version>3.4.0</version>
                <configuration>
                    <configLocation>checkstyle.xml</configLocation>
                    <failOnViolation>true</failOnViolation>
                    <includeTestSourceDirectory>true</includeTestSourceDirectory>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>check</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>

            <!-- PMD: Code Analysis -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-pmd-plugin</artifactId>
                <version>3.22.0</version>
                <configuration>
                    <rulesets>
                        <ruleset>pmd-ruleset.xml</ruleset>
                    </rulesets>
                    <failOnViolation>true</failOnViolation>
                    <printFailingErrors>true</printFailingErrors>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>check</goal>
                            <goal>cpd-check</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 2: Create checkstyle.xml config**

Create `checkstyle.xml` in the project root:

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
        "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
        "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
    <module name="TreeWalker">
        <!-- Naming conventions -->
        <module name="ConstantName"/>
        <module name="LocalFinalVariableName"/>
        <module name="LocalVariableName"/>
        <module name="MemberName"/>
        <module name="MethodName"/>
        <module name="PackageName"/>
        <module name="ParameterName"/>
        <module name="StaticVariableName"/>
        <module name="TypeName"/>

        <!-- Imports -->
        <module name="AvoidStarImport"/>
        <module name="IllegalImport"/>
        <module name="RedundantImport"/>
        <module name="UnusedImports"/>

        <!-- Size violations -->
        <module name="LineLength">
            <property name="max" value="160"/>
            <property name="ignorePattern" value="^import"/>
        </module>
        <module name="MethodLength">
            <property name="max" value="120"/>
        </module>

        <!-- Coding issues -->
        <module name="EmptyBlock"/>
        <module name="EmptyCatchBlock"/>
        <module name="LeftCurly"/>
        <module name="RightCurly"/>
        <module name="NeedBraces"/>
        <module name="WhitespaceAround"/>
    </module>

    <!-- File-level checks -->
    <module name="FileTabCharacter"/>
    <module name="NewlineAtEndOfFile"/>
</module>
```

- [ ] **Step 3: Create pmd-ruleset.xml config**

Create `pmd-ruleset.xml` in the project root:

```xml
<?xml version="1.0"?>
<ruleset name="Custom Rules"
         xmlns="http://pmd.sourceforge.net/ruleset/2.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://pmd.sourceforge.net/ruleset/2.0.0 https://pmd.sourceforge.net/ruleset_2_0_0.xsd">

    <description>PMD ruleset for SuperBizAgent</description>

    <!-- Best practices -->
    <rule ref="category/java/bestpractices.xml">
        <exclude name="GuardLogStatement"/>
        <exclude name="AvoidPrintStackTrace"/>
        <exclude name="SystemPrintln"/>
    </rule>

    <!-- Code style -->
    <rule ref="category/java/codestyle.xml">
        <exclude name="OnlyOneReturn"/>
        <exclude name="AtLeastOneConstructor"/>
        <exclude name="ShortVariable"/>
        <exclude name="LongVariable"/>
        <exclude name="ShortClassName"/>
        <exclude name="LocalVariableCouldBeFinal"/>
        <exclude name="MethodArgumentCouldBeFinal"/>
        <exclude name="DefaultPackage"/>
        <exclude name="CommentDefaultAccessModifier"/>
        <exclude name="CallSuperInConstructor"/>
    </rule>

    <!-- Error-prone -->
    <rule ref="category/java/errorprone.xml">
        <exclude name="DataflowAnomalyAnalysis"/>
        <exclude name="BeanMembersShouldSerialize"/>
        <exclude name="NullAssignment"/>
        <exclude name="AvoidLiteralsInIfCondition"/>
    </rule>

    <!-- Performance -->
    <rule ref="category/java/performance.xml"/>
</ruleset>
```

- [ ] **Step 4: Create spotbugs-exclude.xml config**

Create `spotbugs-exclude.xml` in the project root:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<FindBugsFilter>
    <!-- Exclude Lombok-generated code -->
    <Match>
        <Class name="~.*\$\$.*"/>
    </Match>
    <!-- Exclude DTO classes that use public fields -->
    <Match>
        <Package name="~org\.example\.dto\..*"/>
    </Match>
</FindBugsFilter>
```

- [ ] **Step 5: Add checkstyle and pmd dependencies**

In `pom.xml`, add these dependencies inside `<dependencyManagement><dependencies>` to pin versions:

After the Jackson dependencies, add:

```xml
            <!-- Static analysis dependency versions -->
            <dependency>
                <groupId>com.puppycrawl.tools</groupId>
                <artifactId>checkstyle</artifactId>
                <version>10.17.0</version>
            </dependency>
            <dependency>
                <groupId>net.sourceforge.pmd</groupId>
                <artifactId>pmd-java</artifactId>
                <version>7.4.0</version>
            </dependency>
```

---

### Task 8: Code Quality — Externalize System Prompts

**Files:**
- Modify: `src/main/java/org/example/service/ChatService.java` (load prompt from resource)
- Reference: `src/main/java/org/example/service/AiOpsService.java` (prompts are already in this file — externalization to resource files is a Phase 2 task per improvement-plan.md item #11, skip for now)

**Scope:** Only externalize the `ChatService.buildSystemPrompt()` system prompt text. The AiOps agent prompts are explicitly listed as "Priority: Medium" (item #11), so skip them in Phase 1.

- [ ] **Step 1: Create system prompt resource file**

Create `src/main/resources/prompts/chat-system-prompt.txt`:

```
你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。
当用户询问时间相关问题时，使用 getCurrentDateTime 工具。
当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。
【特别注意】：如果 queryInternalDocs 工具返回的片段中带有 '[用户私人记忆]' 标签，说明这是你与用户在历史对话中提炼出的持久化私人记忆。请务必优先尊重和结合这些私人偏好与事实来提供个性化的回答。
当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。
当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。
当用户需要获取最新互联网资讯或外部知识时，你可以使用 tavily 相关工具搜索网络。
当用户需要查询业务系统数据库结构或数据时，你可以使用 database 相关工具执行查询。
```

- [ ] **Step 2: Update ChatService.java to load prompt from resource file**

In `ChatService.java`, make the following changes:

Add import:
```java
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.Resource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
```

Add field:
```java
@Autowired
private ResourceLoader resourceLoader;
```

Replace `buildSystemPrompt()` method:

Old (lines 49-80):
```java
    public String buildSystemPrompt(List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();

        // 基础系统提示
        systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("【特别注意】：如果 queryInternalDocs 工具返回的片段中带有 '[用户私人记忆]' 标签，说明这是你与用户在历史对话中提炼出的持久化私人记忆。请务必优先尊重和结合这些私人偏好与事实来提供个性化的回答。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n");
        systemPromptBuilder.append("当用户需要获取最新互联网资讯或外部知识时，你可以使用 tavily 相关工具搜索网络。\n");
        systemPromptBuilder.append("当用户需要查询业务系统数据库结构或数据时，你可以使用 database 相关工具执行查询。\n\n");

        // 添加历史消息
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }

        systemPromptBuilder.append("请基于以上对话历史，回答用户的新问题。");

        return systemPromptBuilder.toString();
    }
```

New:
```java
    public String buildSystemPrompt(List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();

        // 从资源文件加载基础系统提示
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/chat-system-prompt.txt");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String basePrompt = reader.lines().collect(Collectors.joining("\n"));
                systemPromptBuilder.append(basePrompt).append("\n\n");
            }
        } catch (Exception e) {
            logger.warn("无法加载系统提示词资源文件，使用默认提示词", e);
            systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询内部文档知识库，以及查询 Prometheus 告警信息。\n\n");
        }

        // 添加历史消息
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }

        systemPromptBuilder.append("请基于以上对话历史，回答用户的新问题。");

        return systemPromptBuilder.toString();
    }
```

---

### Task 9: Verify the Build

- [ ] **Step 1: Run Maven compile to verify no compilation errors**

Run: `cd /Users/distancewk/Downloads/SuperBizAgent-release-2026-01-02 && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Clean up any config file issues**

If checkstyle/PMD plugins cause build failures due to pre-existing code style issues, adjust the config files (relax rules) as needed rather than modifying source code style in this phase.

---

### Task 10: Commit All Changes

- [ ] **Step 1: Stage and commit**

```bash
git add Dockerfile docker-compose.yml .github/workflows/ci.yml
git add src/main/java/org/example/util/ToolUtils.java
git add src/main/java/org/example/service/ChatService.java
git add src/main/java/org/example/service/AiOpsService.java
git add src/main/java/org/example/service/VectorRerankService.java
git add src/main/java/org/example/service/RagService.java
git add src/main/resources/application.yml
git add src/main/resources/prompts/chat-system-prompt.txt
git add checkstyle.xml pmd-ruleset.xml spotbugs-exclude.xml
git add pom.xml
git commit -m "feat: add Docker packaging, CI/CD pipeline, and code quality tooling

- Add multi-stage Dockerfile for application packaging
- Add combined docker-compose.yml (app + Milvus)
- Add GitHub Actions CI workflow
- Extract duplicated buildMethodToolsArray() into shared ToolUtils
- Externalize hardcoded DashScope URLs to application.yml
- Externalize ChatService system prompt to resource file
- Add SpotBugs, Checkstyle, PMD static analysis plugins"
```

---

## Self-Review Checklist

1. **Spec coverage:** All 3 Phase 1 items are covered (Dockerfile/compose, CI/CD, code quality + static analysis + specific fixes).
2. **Placeholder scan:** No TBD, TODO, or vague instructions — every step has exact code and commands.
3. **Type consistency:** Utility method signature matches usage in both ChatService and AiOpsService. Config property names (`dashscope.base-url`, `dashscope.rerank.url`) are consistent across application.yml and both service files.
4. **No over-scope:** AiOps prompts are explicitly deferred (item #11 is Medium priority). The plan only covers what Phase 1 requires.
