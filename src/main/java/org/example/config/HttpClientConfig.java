package org.example.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(50, 5, TimeUnit.MINUTES))
                .addInterceptor(chain -> {
                    String traceparent = MDC.get(TraceContext.TRACEPARENT_HEADER);
                    okhttp3.Request request = chain.request();
                    if (traceparent == null || traceparent.isBlank()
                            || request.header(TraceContext.TRACEPARENT_HEADER) != null) {
                        return chain.proceed(request);
                    }
                    return chain.proceed(request.newBuilder()
                            .header(TraceContext.TRACEPARENT_HEADER, traceparent)
                            .build());
                })
                .build();
    }
}
