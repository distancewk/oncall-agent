package org.example.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class IncidentDataSourceConfig {

    @Bean(destroyMethod = "close")
    public DataSource incidentDataSource(AppIncidentProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("incident-store-pool");
        config.setJdbcUrl(properties.getJdbcUrl());
        config.setUsername(value(properties.getJdbcUsername()));
        config.setPassword(value(properties.getJdbcPassword()));
        config.setMaximumPoolSize(Math.max(1, properties.getJdbcMaximumPoolSize()));
        config.setMinimumIdle(Math.max(0, properties.getJdbcMinimumIdle()));
        config.setConnectionTimeout(Math.max(250L, properties.getJdbcConnectionTimeoutMillis()));
        config.setInitializationFailTimeout(properties.getJdbcInitializationFailTimeoutMillis());
        if (notBlank(properties.getJdbcDriverClassName())) {
            config.setDriverClassName(properties.getJdbcDriverClassName());
        }
        return new HikariDataSource(config);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
