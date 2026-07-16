package org.example.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationContractTest {

    private final ApplicationContextRunner jobPropertiesContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JobPropertiesTestConfiguration.class);

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
        assertTrue(yaml.contains("enabled: ${APP_SECURITY_ENABLED:true}"));
        assertTrue(yaml.contains("webhook-secret: ${APP_WEBHOOK_SECRET:}"));
        assertTrue(yaml.contains("retry-max-attempts: ${APP_RESILIENCE_RETRY_MAX_ATTEMPTS:1}"));
        assertTrue(yaml.contains("retry-max-attempts: ${APP_PROMETHEUS_RETRY_MAX_ATTEMPTS:2}"));
        assertTrue(yaml.contains("retry-max-attempts: ${APP_CLS_RETRY_MAX_ATTEMPTS:2}"));
        assertTrue(yaml.contains(
                "jdbc-url: ${APP_INCIDENT_JDBC_URL:jdbc:postgresql://localhost:5432/superbizagent}"));
        assertTrue(yaml.contains("jdbc-username: ${APP_INCIDENT_JDBC_USERNAME:superbizagent}"));
        assertTrue(yaml.contains(
                "jdbc-initialization-fail-timeout-millis: ${APP_INCIDENT_JDBC_INITIALIZATION_FAIL_TIMEOUT_MILLIS:-1}"));
        assertFalse(yaml.contains("storage-mode:"));
        assertFalse(yaml.contains("APP_INCIDENT_STORAGE_MODE"));
        assertTrue(yaml.contains("lease-duration-millis: ${APP_JOB_LEASE_DURATION_MILLIS:60000}"));
        assertTrue(yaml.contains("heartbeat-interval-millis: ${APP_JOB_HEARTBEAT_INTERVAL_MILLIS:15000}"));
        assertTrue(yaml.contains("worker-concurrency: ${APP_JOB_WORKER_CONCURRENCY:4}"));
        assertTrue(yaml.contains(
                "query-internal-docs-max-calls-per-run: ${APP_QUERY_INTERNAL_DOCS_MAX_CALLS_PER_RUN:3}"));
        assertTrue(yaml.contains("tavily-max-calls-per-run: ${APP_TAVILY_MAX_CALLS_PER_RUN:2}"));
        assertTrue(yaml.contains("dbhub-max-calls-per-run: ${APP_DBHUB_MAX_CALLS_PER_RUN:2}"));
    }

    @Test
    void appJobProperties_shouldExposeDurableWorkerDefaults() {
        AppJobProperties properties = new AppJobProperties();

        assertEquals(60_000L, properties.getLeaseDurationMillis());
        assertEquals(15_000L, properties.getHeartbeatIntervalMillis());
        assertEquals(4, properties.getWorkerConcurrency());
        assertEquals(2, properties.getDiagnosisMaxAttempts());
        assertEquals(3, properties.getIndexMaxAttempts());
    }

    @Test
    void appJobProperties_shouldBindValidDefaultsThroughSpringContext() {
        jobPropertiesContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AppJobProperties properties = context.getBean(AppJobProperties.class);
            assertThat(properties.getWorkerConcurrency()).isEqualTo(4);
            assertThat(properties.getHeartbeatIntervalMillis()).isEqualTo(15_000L);
            assertThat(properties.getLeaseDurationMillis()).isEqualTo(60_000L);
        });
    }

    @Test
    void appJobProperties_shouldRejectNonPositiveWorkerConcurrencyDuringContextStartup() {
        jobPropertiesContextRunner
                .withPropertyValues("app.jobs.worker-concurrency=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("workerConcurrency")
                            .hasStackTraceContaining("must be greater than 0");
                });
    }

    @Test
    void appJobProperties_shouldRejectHeartbeatAtHalfOfLeaseDuringContextStartup() {
        jobPropertiesContextRunner
                .withPropertyValues(
                        "app.jobs.heartbeat-interval-millis=30000",
                        "app.jobs.lease-duration-millis=60000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("heartbeatIntervalMillis")
                            .hasStackTraceContaining("less than half of leaseDurationMillis");
                });
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
    void applicationProdYaml_shouldDisableMocksAndSimulation() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application-prod.yml"));

        assertTrue(yaml.contains("simulate-enabled: false"));
        assertTrue(yaml.contains("prometheus:"));
        assertTrue(yaml.contains("mock-enabled: false"));
        assertTrue(yaml.contains("cls:"));
        assertTrue(yaml.contains("jdbc-initialization-fail-timeout-millis: ${APP_INCIDENT_JDBC_INITIALIZATION_FAIL_TIMEOUT_MILLIS:30000}"));
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
        assertTrue(compose.contains("postgres:\n        condition: service_healthy"));
        assertTrue(compose.contains("POSTGRES_DB: ${POSTGRES_DB:-superbizagent}"));
        assertTrue(compose.contains("APP_INCIDENT_JDBC_URL: ${APP_INCIDENT_JDBC_URL:-jdbc:postgresql://postgres:5432/superbizagent}"));
        assertTrue(compose.contains("APP_INCIDENT_JDBC_USERNAME: ${APP_INCIDENT_JDBC_USERNAME:-superbizagent}"));
        assertTrue(compose.contains("PROMETHEUS_MOCK_ENABLED: ${PROMETHEUS_MOCK_ENABLED:-false}"));
        assertTrue(compose.contains("CLS_MOCK_ENABLED: ${CLS_MOCK_ENABLED:-false}"));
    }

    @Test
    void incidentStore_shouldUsePostgresqlCompatibleSchema() throws Exception {
        String migration = Files.readString(
                Path.of("src/main/resources/db/migration/incidents/V1__create_incidents.sql"));

        assertTrue(migration.contains("create table if not exists incidents"));
        assertTrue(migration.contains("payload text not null"));
        assertTrue(migration.contains("idx_incidents_aggregation_key"));
        assertFalse(migration.contains("payload clob not null"));

        String normalizedMigration = Files.readString(
                Path.of("src/main/resources/db/migration/incidents/V2__create_normalized_operational_tables.sql"));
        assertTrue(normalizedMigration.contains("create table if not exists incident_alerts"));
        assertTrue(normalizedMigration.contains("create table if not exists diagnosis_runs"));
        assertTrue(normalizedMigration.contains("create table if not exists diagnosis_evidence"));
        assertTrue(normalizedMigration.contains("create table if not exists chat_sessions"));
        assertTrue(normalizedMigration.contains("create table if not exists chat_messages"));
        assertTrue(normalizedMigration.contains("create table if not exists index_tasks"));
        assertTrue(normalizedMigration.contains("create table if not exists background_jobs"));
        assertTrue(normalizedMigration.contains("uk_incidents_aggregation_key"));
    }

    @Test
    void incidentStore_shouldUseSpringManagedDataSourcePool() throws Exception {
        String source = Files.readString(Path.of("src/main/java/org/example/service/IncidentStore.java"));
        String config = Files.readString(Path.of("src/main/java/org/example/config/IncidentDataSourceConfig.java"));
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        String pom = Files.readString(Path.of("pom.xml"));

        assertTrue(source.contains("javax.sql.DataSource"));
        assertTrue(source.contains("dataSource.getConnection()"));
        assertFalse(source.contains("DriverManager"));
        assertTrue(config.contains("HikariDataSource"));
        assertTrue(yaml.contains("jdbc-maximum-pool-size: ${APP_INCIDENT_JDBC_MAX_POOL_SIZE:10}"));
        assertTrue(yaml.contains("jdbc-connection-timeout-millis: ${APP_INCIDENT_JDBC_CONNECTION_TIMEOUT_MILLIS:5000}"));
        assertTrue(config.contains("properties.getJdbcInitializationFailTimeoutMillis()"));
        assertFalse(config.contains("setInitializationFailTimeout(-1L)"));
        assertTrue(pom.contains("spring-boot-starter-jdbc"));
    }

    @Test
    void incidentStore_shouldUseFlywayMigrationInsteadOfInlineDdl() throws Exception {
        String source = Files.readString(Path.of("src/main/java/org/example/service/IncidentStore.java"));
        String migrator = Files.readString(Path.of("src/main/java/org/example/service/IncidentSchemaMigrator.java"));
        String pom = Files.readString(Path.of("pom.xml"));

        assertFalse(source.contains("create table if not exists incidents"));
        assertTrue(source.contains("schemaMigrator.migrate()"));
        assertTrue(migrator.contains("org.flywaydb.core.Flyway"));
        assertTrue(migrator.contains("classpath:db/migration/incidents"));
        assertTrue(pom.contains("flyway-core"));
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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppJobProperties.class)
    static class JobPropertiesTestConfiguration {
    }
}
