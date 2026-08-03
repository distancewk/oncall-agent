package db.migration.incidents;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Makes tenant-scoped identifiers unique only within their tenant. */
public class V13__add_tenant_composite_keys extends BaseJavaMigration {

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public void migrate(Context context) throws Exception {
        // Flyway owns the migration connection and closes it after the migration.
        Connection connection = context.getConnection();
        dropConstraintIfPresent(connection, "diagnosis_evidence", "fk_diagnosis_evidence_run");
        dropConstraintIfPresent(connection, "chat_messages", "fk_chat_messages_session");

        dropPrimaryKey(connection, "diagnosis_runs");
        dropPrimaryKey(connection, "diagnosis_evidence");
        dropPrimaryKey(connection, "chat_sessions");
        dropPrimaryKey(connection, "index_tasks");

        addConstraint(connection, "alter table diagnosis_runs add constraint pk_diagnosis_runs_tenant_run "
                + "primary key (tenant_id, run_id)");
        addConstraint(connection, "alter table diagnosis_evidence add constraint pk_diagnosis_evidence_tenant_id "
                + "primary key (tenant_id, id)");
        addConstraint(connection, "alter table chat_sessions add constraint pk_chat_sessions_tenant_session "
                + "primary key (tenant_id, session_id)");
        addConstraint(connection, "alter table index_tasks add constraint pk_index_tasks_tenant_task "
                + "primary key (tenant_id, task_id)");

        addConstraint(connection, "alter table diagnosis_evidence add constraint fk_diagnosis_evidence_run_tenant "
                + "foreign key (tenant_id, run_id) references diagnosis_runs (tenant_id, run_id) on delete cascade");
        addConstraint(connection, "alter table chat_messages add constraint fk_chat_messages_session_tenant "
                + "foreign key (tenant_id, session_id) references chat_sessions (tenant_id, session_id) on delete cascade");
    }

    private void dropPrimaryKey(Connection connection, String table) throws SQLException {
        String constraint = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                select constraint_name
                from information_schema.table_constraints
                where upper(table_name) = upper(?) and constraint_type = 'PRIMARY KEY'
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    constraint = resultSet.getString(1);
                }
            }
        }
        if (constraint != null) {
            addConstraint(connection, "alter table " + identifier(table)
                    + " drop constraint " + identifier(constraint));
        }
    }

    private void dropConstraintIfPresent(Connection connection, String table, String constraint)
            throws SQLException {
        addConstraint(connection, "alter table " + identifier(table)
                + " drop constraint if exists " + identifier(constraint));
    }

    private void addConstraint(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private String identifier(String value) {
        // These names come only from fixed table names or database metadata and
        // are valid unquoted identifiers in both PostgreSQL and H2.
        return value;
    }
}
