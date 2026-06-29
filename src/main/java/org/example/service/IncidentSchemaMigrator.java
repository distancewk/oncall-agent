package org.example.service;

import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Service
public class IncidentSchemaMigrator {

    private static final String MIGRATION_LOCATION = "classpath:db/migration/incidents";

    private final DataSource dataSource;
    private boolean migrated;

    public IncidentSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public synchronized void migrate() {
        if (migrated) {
            return;
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .load()
                .migrate();
        migrated = true;
    }
}
