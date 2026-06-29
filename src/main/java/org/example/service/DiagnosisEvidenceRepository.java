package org.example.service;

import org.example.dto.DiagnosisEvidence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class DiagnosisEvidenceRepository {

    private final DataSource dataSource;

    public DiagnosisEvidenceRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    DiagnosisEvidenceRepository() {
        this(null);
    }

    public void save(Connection connection, String runId, DiagnosisEvidence evidence) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                merge into diagnosis_evidence as target
                using (values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)) as source (
                    id, run_id, type, title, content, tool_name, query_params, time_range,
                    summary, raw_fragment, success, error_message, error_code,
                    attempt_count, duration_ms, retryable, created_at
                )
                on target.id = source.id
                when matched then update set
                    title = source.title,
                    content = source.content,
                    summary = source.summary,
                    raw_fragment = source.raw_fragment,
                    error_message = source.error_message,
                    error_code = source.error_code,
                    attempt_count = source.attempt_count,
                    duration_ms = source.duration_ms,
                    retryable = source.retryable
                when not matched then insert (
                    id, run_id, type, title, content, tool_name, query_params, time_range,
                    summary, raw_fragment, success, error_message, error_code,
                    attempt_count, duration_ms, retryable, created_at
                ) values (
                    source.id, source.run_id, source.type, source.title, source.content,
                    source.tool_name, source.query_params, source.time_range, source.summary,
                    source.raw_fragment, source.success, source.error_message, source.error_code,
                    source.attempt_count, source.duration_ms, source.retryable, source.created_at
                )
                """)) {
            bind(statement, runId, evidence);
            statement.executeUpdate();
        }
    }

    public void insert(Connection connection, String runId, DiagnosisEvidence evidence) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into diagnosis_evidence (
                    id, run_id, type, title, content, tool_name, query_params, time_range,
                    summary, raw_fragment, success, error_message, error_code,
                    attempt_count, duration_ms, retryable, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, runId, evidence);
            statement.executeUpdate();
        }
    }

    public List<DiagnosisEvidence> findByRunId(String runId) {
        try (Connection connection = dataSource.getConnection()) {
            return findByRunId(connection, runId);
        } catch (Exception e) {
            throw new IllegalStateException("读取诊断证据失败: " + runId, e);
        }
    }

    public List<DiagnosisEvidence> findByRunId(Connection connection, String runId) throws Exception {
        List<DiagnosisEvidence> evidence = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select * from diagnosis_evidence
                where run_id = ?
                order by created_at, id
                """)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    evidence.add(map(resultSet));
                }
            }
            return evidence;
        }
    }

    private DiagnosisEvidence map(ResultSet resultSet) throws Exception {
        DiagnosisEvidence evidence = new DiagnosisEvidence();
        evidence.setId(resultSet.getString("id"));
        evidence.setType(resultSet.getString("type"));
        evidence.setTitle(resultSet.getString("title"));
        evidence.setContent(resultSet.getString("content"));
        evidence.setToolName(resultSet.getString("tool_name"));
        evidence.setQueryParams(resultSet.getString("query_params"));
        evidence.setTimeRange(resultSet.getString("time_range"));
        evidence.setSummary(resultSet.getString("summary"));
        evidence.setRawFragment(resultSet.getString("raw_fragment"));
        evidence.setSuccess(resultSet.getBoolean("success"));
        evidence.setErrorMessage(resultSet.getString("error_message"));
        evidence.setErrorCode(resultSet.getString("error_code"));
        evidence.setAttemptCount(resultSet.getInt("attempt_count"));
        evidence.setDurationMs(resultSet.getLong("duration_ms"));
        evidence.setRetryable(resultSet.getBoolean("retryable"));
        evidence.setCreatedAt(resultSet.getLong("created_at"));
        return evidence;
    }

    private void bind(PreparedStatement statement,
                      String runId,
                      DiagnosisEvidence evidence) throws Exception {
        int index = 1;
        statement.setString(index++, evidence.getId());
        statement.setString(index++, runId);
        statement.setString(index++, evidence.getType());
        statement.setString(index++, evidence.getTitle());
        statement.setString(index++, evidence.getContent());
        statement.setString(index++, evidence.getToolName());
        statement.setString(index++, evidence.getQueryParams());
        statement.setString(index++, evidence.getTimeRange());
        statement.setString(index++, evidence.getSummary());
        statement.setString(index++, evidence.getRawFragment());
        statement.setBoolean(index++, evidence.isSuccess());
        statement.setString(index++, evidence.getErrorMessage());
        statement.setString(index++, evidence.getErrorCode());
        statement.setInt(index++, evidence.getAttemptCount());
        statement.setLong(index++, evidence.getDurationMs());
        statement.setBoolean(index++, evidence.isRetryable());
        statement.setLong(index, evidence.getCreatedAt());
    }
}
