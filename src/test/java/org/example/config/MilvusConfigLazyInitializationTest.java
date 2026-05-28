package org.example.config;

import io.milvus.client.MilvusServiceClient;
import org.example.client.MilvusClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MilvusConfigLazyInitializationTest {

    private static final AtomicInteger createClientCalls = new AtomicInteger();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MilvusConfig.class, ThrowingFactoryConfig.class, LazyConsumerConfig.class);

    @BeforeEach
    void resetCalls() {
        createClientCalls.set(0);
    }

    @Test
    void contextStartsWithoutCreatingMilvusClientWhenInjectedLazily() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LazyMilvusConsumer.class);
            assertThat(createClientCalls).hasValue(0);
        });
    }

    @Configuration
    static class ThrowingFactoryConfig {

        @Bean
        MilvusClientFactory milvusClientFactory() {
            MilvusClientFactory factory = mock(MilvusClientFactory.class);
            when(factory.createClient()).thenAnswer(invocation -> {
                createClientCalls.incrementAndGet();
                throw new RuntimeException("Milvus should not be created during context startup");
            });
            return factory;
        }

        @Bean
        MilvusProperties milvusProperties() {
            return new MilvusProperties();
        }
    }

    @Configuration
    static class LazyConsumerConfig {

        @Bean
        LazyMilvusConsumer lazyMilvusConsumer() {
            return new LazyMilvusConsumer();
        }
    }

    static class LazyMilvusConsumer {

        @Autowired
        @Lazy
        private MilvusServiceClient milvusClient;
    }
}
