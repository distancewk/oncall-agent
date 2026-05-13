package org.example.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
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
        assertTrue(yaml.contains("search-ef: ${RAG_SEARCH_EF:64}"));
        assertTrue(yaml.contains("enabled: ${APP_SECURITY_ENABLED:false}"));
        assertTrue(yaml.contains("webhook-secret: ${APP_WEBHOOK_SECRET:}"));
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
    }

    @Test
    void frontend_shouldSanitizeRenderedMarkdown() throws Exception {
        String index = Files.readString(Path.of("src/main/resources/static/index.html"));
        String app = Files.readString(Path.of("src/main/resources/static/app.js"));

        assertTrue(index.contains("dompurify"));
        assertTrue(app.contains("DOMPurify.sanitize"));
    }
}
