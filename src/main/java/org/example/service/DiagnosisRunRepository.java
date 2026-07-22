package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DiagnosisRunRepository {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final DiagnosisEvidenceRepository evidenceRepository;

    public DiagnosisRunRepository(DataSource dataSource,
                                  ObjectMapper objectMapper,
                                  DiagnosisEvidenceRepository evidenceRepository) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.evidenceRepository = evidenceRepository;
    }

    DiagnosisRunRepository(ObjectMapper objectMapper,
                           DiagnosisEvidenceRepository evidenceRepository) {
        this(null, objectMapper, evidenceRepository);
    }

    public void save(Connection connection, DiagnosisRunRecord run) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                merge into diagnosis_runs as target
                using (values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?))
                as source (
                    run_id, incident_id, status, created_at, started_at, completed_at,
                    alert_context, report, error_message, current_step, progress_message,
                    current_tool, reused_from_run_id, reuse_reason, reuse_confidence,
                    reuse_validated_at, quality_score, quality_grade, quality_summary,
                    quality_issues, human_review_status, human_review_comment,
                    human_reviewed_at, case_archived, case_document_id, case_archive_message, version
                )
                on target.run_id = source.run_id and target.version = source.version
                when matched then update set
                    status = source.status,
                    started_at = source.started_at,
                    completed_at = source.completed_at,
                    alert_context = source.alert_context,
                    report = source.report,
                    error_message = source.error_message,
                    current_step = source.current_step,
                    progress_message = source.progress_message,
                    current_tool = source.current_tool,
                    reused_from_run_id = source.reused_from_run_id,
                    reuse_reason = source.reuse_reason,
                    reuse_confidence = source.reuse_confidence,
                    reuse_validated_at = source.reuse_validated_at,
                    quality_score = source.quality_score,
                    quality_grade = source.quality_grade,
                    quality_summary = source.quality_summary,
                    quality_issues = source.quality_issues,
                    human_review_status = source.human_review_status,
                    human_review_comment = source.human_review_comment,
                    human_reviewed_at = source.human_reviewed_at,
                    case_archived = source.case_archived,
                    case_document_id = source.case_document_id,
                    case_archive_message = source.case_archive_message,
                    version = target.version + 1
                when not matched then insert (
                    run_id, incident_id, status, created_at, started_at, completed_at,
                    alert_context, report, error_message, current_step, progress_message,
                    current_tool, reused_from_run_id, reuse_reason, reuse_confidence,
                    reuse_validated_at, quality_score, quality_grade, quality_summary,
                    quality_issues, human_review_status, human_review_comment,
                    human_reviewed_at, case_archived, case_document_id, case_archive_message, version
                ) values (
                    source.run_id, source.incident_id, source.status, source.created_at,
                    source.started_at, source.completed_at, source.alert_context, source.report,
                    source.error_message, source.current_step, source.progress_message,
                    source.current_tool, source.reused_from_run_id, source.reuse_reason,
                    source.reuse_confidence, source.reuse_validated_at, source.quality_score,
                    source.quality_grade, source.quality_summary, source.quality_issues,
                    source.human_review_status, source.human_review_comment,
                    source.human_reviewed_at, source.case_archived, source.case_document_id,
                    source.case_archive_message, source.version
                )
                """)) {
            bind(statement, run);
            statement.executeUpdate();
            try (PreparedStatement versionStatement = connection.prepareStatement(
                    "select version from diagnosis_runs where run_id = ?")) {
                versionStatement.setString(1, run.getRunId());
                try (ResultSet resultSet = versionStatement.executeQuery()) {
                    if (resultSet.next()) {
                        run.setVersion(resultSet.getLong(1));
                    }
                }
            }
        }
        for (var evidence : run.getEvidence()) {
            evidenceRepository.save(connection, run.getRunId(), evidence);
        }
    }

    public void insert(Connection connection, DiagnosisRunRecord run) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into diagnosis_runs (
                    run_id, incident_id, status, created_at, started_at, completed_at,
                    alert_context, report, error_message, current_step, progress_message,
                    current_tool, reused_from_run_id, reuse_reason, reuse_confidence,
                    reuse_validated_at, quality_score, quality_grade, quality_summary,
                    quality_issues, human_review_status, human_review_comment,
                    human_reviewed_at, case_archived, case_document_id, case_archive_message, version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, run);
            statement.executeUpdate();
        }
        for (var evidence : run.getEvidence()) {
            evidenceRepository.insert(connection, run.getRunId(), evidence);
        }
    }

    public List<DiagnosisRunRecord> findByIncidentId(String incidentId) {
        List<DiagnosisRunRecord> runs = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from diagnosis_runs
                     where incident_id = ?
                     order by created_at, run_id
                     """)) {
            statement.setString(1, incidentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    DiagnosisRunRecord run = map(resultSet);
                    run.setEvidence(evidenceRepository.findByRunId(run.getRunId()));
                    runs.add(run);
                }
            }
            return runs;
        } catch (Exception e) {
            throw new IllegalStateException("读取诊断运行失败: " + incidentId, e);
        }
    }

    public Optional<DiagnosisRunRecord> findActiveByIncidentId(Connection connection,
                                                                String incidentId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select * from diagnosis_runs
                where incident_id = ?
                  and status in ('QUEUED', 'RUNNING', 'WAITING_TOOL')
                order by created_at desc, run_id desc
                limit 1
                """)) {
            statement.setString(1, incidentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                DiagnosisRunRecord run = map(resultSet);
                run.setEvidence(evidenceRepository.findByRunId(connection, run.getRunId()));
                return Optional.of(run);
            }
        }
    }

    public Map<String, List<DiagnosisRunRecord>> findByIncidentIds(Connection connection,
                                                                     List<String> incidentIds)
            throws Exception {
        Map<String, List<DiagnosisRunRecord>> result = new HashMap<>();
        if (incidentIds == null || incidentIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(incidentIds.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement(
                "select * from diagnosis_runs where incident_id in (" + placeholders
                        + ") order by created_at, run_id")) {
            for (int index = 0; index < incidentIds.size(); index++) {
                statement.setString(index + 1, incidentIds.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    DiagnosisRunRecord run = map(resultSet);
                    result.computeIfAbsent(run.getIncidentId(), ignored -> new ArrayList<>()).add(run);
                }
            }
        }
        return result;
    }

    public boolean cancelActive(Connection connection,
                                String incidentId,
                                String runId,
                                String reason,
                                long now) throws Exception {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                update diagnosis_runs
                set status = 'CANCELLED',
                    started_at = case
                        when started_at is null or started_at = 0 then ?
                        else started_at
                    end,
                    completed_at = ?,
                    error_message = ?,
                    current_tool = null,
                    current_step = '诊断已取消',
                    progress_message = ?,
                    version = version + 1
                where incident_id = ?
                  and run_id = ?
                  and status in ('QUEUED', 'RUNNING', 'WAITING_TOOL')
                """)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, reason);
            statement.setString(4, reason);
            statement.setString(5, incidentId);
            statement.setString(6, runId);
            updated = statement.executeUpdate();
        }
        if (updated > 0) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    update incidents
                    set updated_at = ?, version = version + 1
                    where id = ?
                    """)) {
                statement.setLong(1, now);
                statement.setString(2, incidentId);
                statement.executeUpdate();
            }
        }
        return updated > 0;
    }

    public boolean updateCaseArchive(Connection connection,
                                     String incidentId,
                                     String runId,
                                     boolean archived,
                                     String documentId,
                                     String message) throws SQLException {
        String stateGuard = archived ? "" : " and case_archived = false";
        String sql = """
                update diagnosis_runs
                set case_archived = ?,
                    case_document_id = ?,
                    case_archive_message = ?,
                    version = version + 1
                where incident_id = ? and run_id = ?
                """ + stateGuard;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, archived);
            statement.setString(2, documentId);
            statement.setString(3, message);
            statement.setString(4, incidentId);
            statement.setString(5, runId);
            return statement.executeUpdate() > 0;
        }
    }

    public Optional<DiagnosisRunRecord> findById(Connection connection,
                                                 String incidentId,
                                                 String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select * from diagnosis_runs
                where incident_id = ? and run_id = ?
                """)) {
            statement.setString(1, incidentId);
            statement.setString(2, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                DiagnosisRunRecord run = map(resultSet);
                run.setEvidence(evidenceRepository.findByRunId(connection, runId));
                return Optional.of(run);
            }
        }
    }

    public boolean updateToolState(Connection connection,
                                   String incidentId,
                                   String runId,
                                   long expectedVersion,
                                   String status,
                                   String currentTool,
                                   String currentStep,
                                   String progressMessage,
                                   long now) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                update diagnosis_runs
                set status = ?,
                    started_at = case when started_at is null or started_at = 0 then ? else started_at end,
                    current_tool = ?,
                    current_step = ?,
                    progress_message = ?,
                    version = version + 1
                where incident_id = ?
                  and run_id = ?
                  and version = ?
                  and status in ('QUEUED', 'RUNNING', 'WAITING_TOOL')
                """)) {
            statement.setString(1, status);
            statement.setLong(2, now);
            statement.setString(3, currentTool);
            statement.setString(4, currentStep);
            statement.setString(5, progressMessage);
            statement.setString(6, incidentId);
            statement.setString(7, runId);
            statement.setLong(8, expectedVersion);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean appendToolEvidence(Connection connection,
                                      String incidentId,
                                      String runId,
                                      long expectedVersion,
                                      DiagnosisEvidence evidence) throws Exception {
        if (!updateToolState(connection, incidentId, runId, expectedVersion,
                "RUNNING", null, "已完成工具调用 " + evidence.getToolName(), evidence.getSummary(),
                System.currentTimeMillis())) {
            return false;
        }
        evidenceRepository.insert(connection, runId, evidence);
        return true;
    }

    public Optional<DiagnosisRunRecord> failActiveStaleRun(Connection connection,
                                                           String incidentId,
                                                           String runId,
                                                           long staleBeforeOrAt,
                                                           long now,
                                                           String message,
                                                           org.example.dto.DiagnosisEvidence evidence)
            throws Exception {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                update diagnosis_runs
                set status = 'FAILED',
                    completed_at = ?,
                    error_message = ?,
                    current_tool = null,
                    current_step = '诊断超时失败',
                    progress_message = ?,
                    version = version + 1
                where incident_id = ?
                  and run_id = ?
                  and status in ('QUEUED', 'RUNNING', 'WAITING_TOOL')
                  and case
                        when started_at is null or started_at = 0 then created_at
                        else started_at
                      end <= ?
                """)) {
            statement.setLong(1, now);
            statement.setString(2, message);
            statement.setString(3, message);
            statement.setString(4, incidentId);
            statement.setString(5, runId);
            statement.setLong(6, staleBeforeOrAt);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            return Optional.empty();
        }
        evidenceRepository.insert(connection, runId, evidence);
        try (PreparedStatement statement = connection.prepareStatement("""
                update incidents
                set updated_at = ?, version = version + 1
                where id = ?
                """)) {
            statement.setLong(1, now);
            statement.setString(2, incidentId);
            statement.executeUpdate();
        }
        return findById(connection, incidentId, runId);
    }

    private void bind(PreparedStatement statement, DiagnosisRunRecord run) throws Exception {
        int index = 1;
        statement.setString(index++, run.getRunId());
        statement.setString(index++, run.getIncidentId());
        statement.setString(index++, run.getStatus());
        statement.setLong(index++, run.getCreatedAt());
        setNullableLong(statement, index++, run.getStartedAt());
        setNullableLong(statement, index++, run.getCompletedAt());
        statement.setString(index++, run.getAlertContext());
        statement.setString(index++, run.getReport());
        statement.setString(index++, run.getErrorMessage());
        statement.setString(index++, run.getCurrentStep());
        statement.setString(index++, run.getProgressMessage());
        statement.setString(index++, run.getCurrentTool());
        statement.setString(index++, run.getReusedFromRunId());
        statement.setString(index++, run.getReuseReason());
        statement.setDouble(index++, run.getReuseConfidence());
        setNullableLong(statement, index++, run.getReuseValidatedAt());
        statement.setInt(index++, run.getQualityScore());
        statement.setString(index++, run.getQualityGrade());
        statement.setString(index++, run.getQualitySummary());
        statement.setString(index++, objectMapper.writeValueAsString(run.getQualityIssues()));
        statement.setString(index++, run.getHumanReviewStatus());
        statement.setString(index++, run.getHumanReviewComment());
        setNullableLong(statement, index++, run.getHumanReviewedAt());
        statement.setBoolean(index++, run.isCaseArchived());
        statement.setString(index++, run.getCaseDocumentId());
        statement.setString(index++, run.getCaseArchiveMessage());
        statement.setLong(index, run.getVersion());
    }

    private DiagnosisRunRecord map(ResultSet resultSet) throws Exception {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId(resultSet.getString("run_id"));
        run.setIncidentId(resultSet.getString("incident_id"));
        run.setStatus(resultSet.getString("status"));
        run.setCreatedAt(resultSet.getLong("created_at"));
        run.setStartedAt(resultSet.getLong("started_at"));
        run.setCompletedAt(resultSet.getLong("completed_at"));
        run.setAlertContext(resultSet.getString("alert_context"));
        run.setReport(resultSet.getString("report"));
        run.setErrorMessage(resultSet.getString("error_message"));
        run.setCurrentStep(resultSet.getString("current_step"));
        run.setProgressMessage(resultSet.getString("progress_message"));
        run.setCurrentTool(resultSet.getString("current_tool"));
        run.setReusedFromRunId(resultSet.getString("reused_from_run_id"));
        run.setReuseReason(resultSet.getString("reuse_reason"));
        run.setReuseConfidence(resultSet.getDouble("reuse_confidence"));
        run.setReuseValidatedAt(resultSet.getLong("reuse_validated_at"));
        run.setQualityScore(resultSet.getInt("quality_score"));
        run.setQualityGrade(resultSet.getString("quality_grade"));
        run.setQualitySummary(resultSet.getString("quality_summary"));
        String issues = resultSet.getString("quality_issues");
        run.setQualityIssues(issues == null
                ? List.of()
                : objectMapper.readValue(issues, new TypeReference<List<String>>() { }));
        run.setHumanReviewStatus(resultSet.getString("human_review_status"));
        run.setHumanReviewComment(resultSet.getString("human_review_comment"));
        run.setHumanReviewedAt(resultSet.getLong("human_reviewed_at"));
        run.setCaseArchived(resultSet.getBoolean("case_archived"));
        run.setCaseDocumentId(resultSet.getString("case_document_id"));
        run.setCaseArchiveMessage(resultSet.getString("case_archive_message"));
        run.setVersion(resultSet.getLong("version"));
        return run;
    }

    private void setNullableLong(PreparedStatement statement, int index, long value) throws Exception {
        if (value <= 0L) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
