package org.example.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationContractTest {

    @Test
    void applicationYaml_shouldExposeDeploymentOverrides() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertTrue(yaml.contains("host: ${MILVUS_HOST:localhost}"));
        assertTrue(yaml.contains("port: ${MILVUS_PORT:19530}"));
        assertTrue(yaml.contains("path: ${FILE_UPLOAD_PATH:./uploads}"));
        assertTrue(yaml.contains("base-url: ${PROMETHEUS_BASE_URL:http://localhost:9090}"));
        assertTrue(yaml.contains("mock-enabled: ${PROMETHEUS_MOCK_ENABLED:false}"));
        assertTrue(yaml.contains("mock-enabled: ${CLS_MOCK_ENABLED:false}"));
        assertTrue(yaml.contains("search-ef: ${RAG_SEARCH_EF:64}"));
        assertTrue(yaml.contains("enabled: ${APP_SECURITY_ENABLED:false}"));
        assertTrue(yaml.contains("webhook-secret: ${APP_WEBHOOK_SECRET:}"));
    }

    @Test
    void applicationDevYaml_shouldEnableLocalAIOpsMocks() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application-dev.yml"));

        assertTrue(yaml.contains("simulate-enabled: true"));
        assertTrue(yaml.contains("prometheus:"));
        assertTrue(yaml.contains("mock-enabled: ${PROMETHEUS_MOCK_ENABLED:true}"));
        assertTrue(yaml.contains("cls:"));
        assertTrue(yaml.contains("mock-enabled: ${CLS_MOCK_ENABLED:true}"));
    }

    @Test
    void applicationMcpYaml_shouldConfigureTavilyStdioConnectionOnly() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application-mcp.yml"));

        assertTrue(yaml.contains("enabled: ${MCP_CLIENT_ENABLED:true}"));
        assertTrue(yaml.contains("tavily:"));
        assertTrue(yaml.contains("${MCP_TAVILY_PACKAGE:tavily-mcp@"));
        assertTrue(yaml.contains("TAVILY_API_KEY: ${TAVILY_API_KEY:}"));
        assertTrue(!yaml.contains("dbhub:"));
    }

    @Test
    void applicationMcpDbYaml_shouldConfigureDbHubStdioConnectionSeparately() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application-mcp-db.yml"));

        assertTrue(yaml.contains("dbhub:"));
        assertTrue(yaml.contains("${MCP_DBHUB_PACKAGE:@bytebase/dbhub@"));
        assertTrue(yaml.contains("--config"));
        assertTrue(yaml.contains("${MCP_DBHUB_CONFIG:./config/dbhub.toml}"));
    }

    @Test
    void applicationMcpYaml_shouldBindDisabledProfileWithoutStartingMcpServers() throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test-env",
                Map.of("MCP_CLIENT_ENABLED", "false")
        ));

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        loader.load("application-mcp", new FileSystemResource("src/main/resources/application-mcp.yml"))
                .forEach(propertySource -> environment.getPropertySources().addLast(propertySource));

        Binder binder = Binder.get(environment);
        Boolean enabled = binder.bind("spring.ai.mcp.client.enabled", Boolean.class)
                .orElseThrow(() -> new IllegalStateException("mcp enabled property did not bind"));
        Map<String, Object> tavily = binder.bind("spring.ai.mcp.client.stdio.connections.tavily",
                        Bindable.mapOf(String.class, Object.class))
                .orElseThrow(() -> new IllegalStateException("tavily connection did not bind"));

        assertEquals(false, enabled);
        assertEquals("npx", tavily.get("command"));
        assertTrue(String.valueOf(tavily.get("args")).contains("tavily-mcp@"));
    }

    @Test
    void dbHubConfig_shouldProvideReadonlyMultiDatabaseExamples() throws Exception {
        String config = Files.readString(Path.of("config/dbhub.toml"));

        assertTrue(config.contains("readonly = true"));
        assertTrue(!config.contains("dsn = \"sqlite:///:memory:\""));
        assertTrue(config.contains("postgres://"));
        assertTrue(config.contains("mysql://"));
        assertTrue(config.contains("mariadb://"));
        assertTrue(config.contains("sqlserver://"));
    }

    @Test
    void milvusProperties_shouldBindHostFromEnvironmentOverride() throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test-env",
                Map.of("MILVUS_HOST", "milvus")
        ));

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        loader.load("application", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(propertySource -> environment.getPropertySources().addLast(propertySource));

        MilvusProperties properties = Binder.get(environment)
                .bind("milvus", MilvusProperties.class)
                .orElseThrow(() -> new IllegalStateException("milvus properties did not bind"));

        assertEquals("milvus", properties.getHost());
    }

    @Test
    void pom_shouldPreloadByteBuddyAgentForMockitoTests() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertTrue(pom.contains("maven-surefire-plugin"));
        assertTrue(pom.contains("-javaagent:${settings.localRepository}/net/bytebuddy/byte-buddy-agent/"));
        assertTrue(pom.contains("byte-buddy-agent-${byte-buddy.version}.jar"));
    }

    @Test
    void dockerCompose_shouldWaitForCoreServicesToBeHealthyBeforeStartingApp() throws Exception {
        String compose = Files.readString(Path.of("docker-compose.yml"));

        assertTrue(compose.contains("milvus:\n        condition: service_healthy"));
        assertTrue(compose.contains("redis:\n        condition: service_healthy"));
        assertTrue(compose.contains("PROMETHEUS_MOCK_ENABLED: ${PROMETHEUS_MOCK_ENABLED:-true}"));
        assertTrue(compose.contains("CLS_MOCK_ENABLED: ${CLS_MOCK_ENABLED:-true}"));
    }

    @Test
    void frontend_shouldSanitizeRenderedMarkdown() throws Exception {
        String index = Files.readString(Path.of("src/main/resources/static/index.html"));
        String app = Files.readString(Path.of("src/main/resources/static/app.js"));

        assertTrue(index.contains("dompurify"));
        assertTrue(app.contains("DOMPurify.sanitize"));
    }

    @Test
    void frontend_shouldRenderMetricTrendChartForTrendEvidence() throws Exception {
        String app = Files.readString(Path.of("src/main/resources/static/app.js"));
        String styles = Files.readString(Path.of("src/main/resources/static/styles.css"));

        assertTrue(app.contains("renderMetricTrendChart"));
        assertTrue(app.contains("extractMetricTrendPayload"));
        assertTrue(app.contains("queryMetricTrend"));
        assertTrue(app.contains("trend-chart-svg"));
        assertTrue(styles.contains(".metric-trend-chart"));
        assertTrue(styles.contains(".trend-chart-svg"));
    }
}
