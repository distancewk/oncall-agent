package org.example.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the project's content-free Spring AI observation handler. */
@Configuration
public class AgentObservationConfig {

    @Bean
    ObservationRegistryCustomizer<ObservationRegistry> agentObservationCustomizer(
            AgentObservationHandler handler) {
        return registry -> registry.observationConfig().observationHandler(handler);
    }
}
