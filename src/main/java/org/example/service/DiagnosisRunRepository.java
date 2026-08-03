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
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                update diagnosis_runs
                set status = ?,
                    started_at = ?,
                    completed_at = ?,
                    alert_context = ?,
                    report = ?,
                    error_message = ?,
                    current_step = ?,
                    progress_message = ?,
                    current_tool = ?,
                    runbook_id = ?,
                    runbook_status = ?,
                    runbook_step = ?,
                    runbook_required_tools = ?,
                    runbook_completed_tools = ?,
                    reused_from_run_id = ?,
                    reuse_reason = ?,
                    reuse_confidence = ?,
                    reuse_validated_at = ?,
                    quality_score = ?,
                    quality_grade = ?,
                    quality_summary = ?,
                    quality_issues = ?,
                    human_review_status = ?,
                    human_review_comment = ?,
                    human_reviewed_at = ?,
                    case_archived = ?,
                    case_archive_status = ?,
                    case_document_id = ?,
                    case_archive_message = ?,
                    version = version + 1
                where run_id = ? and version = ? and tenant_id = ?
                """)) {
            bindForUpdate(statement, run);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            if (findById(connection, run.getIncidentId(), run.getRunId()).isPresent()) {
                throw new IllegalStateException("DiagnosisRun 更新冲突: " + run.getRunId());
            }
            insertRunRecord(connection, run);
        }
        try (PreparedStatement versionStatement = connection.prepareStatement(
                "select version from diagnosis_runs where tenant_id = ? and run_id = ?")) {
            versionStatement.setString(1, TenantContext.currentTenant());
            versionStatement.setString(2, run.getRunId());
            try (ResultSet resultSet = versionStatement.executeQuery()) {
                if (resultSet.next()) {
                    run.setVersion(resultSet.getLong(1));
                }
            }
        }
        for (var evidence : run.getEvidence()) {
            evidenceRepository.save(connection, run.getRunId(), evidence);
        }
    }

    public void insert(Connection connection, DiagnosisRunRecord run) throws Exception {
        insertRunRecord(connection, run);
        for (var evidence : run.getEvidence()) {
            evidenceRepository.insert(connection, run.getRunId(), evidence);
        }
    }

    public List<DiagnosisRunRecord> findByIncidentId(String incidentId) {
        List<DiagnosisRunRecord> runs = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from diagnosis_runs
                     where tenant_id = ? and incident_id = ?
                     order by created_at, run_id
                     """)) {
            statement.setString(1, TenantContext.currentTenant());
            statement.setString(2, incidentId);
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
                where tenant_id = ? and incident_id = ?
                  and status in ('QUEUED', 'RUNNING', 'WAITING_TOOL')
                order by created_at desc, run_id desc
                limit 1
                """)) {
            statement.setString(1, TenantContext.currentTenant());
            statement.setString(2, incidentId);
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
                "select * from diagnosis_runs where tenant_id = ? and incident_id in (" + placeholders
                        + ") order by created_at, run_id")) {
            statement.setString(1, TenantContext.currentTenant());
            for (int index = 0; index < incidentIds.size(); index++) {
                statement.setString(index + 2, incidentIds.get(index));
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
                where tenant_id = ?
                  and incident_id = ?
                  and run_id = ?
                  and status in ('QUEUED', 'RUNNING', 'WAITING_TOOL')
                """)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, reason);
            statement.setString(4, reason);
            statement.setString(5, TenantContext.currentTenant());
            statement.setString(6, incidentId);
            statement.setString(7, runId);
            updated = statement.executeUpdate();
        }
        if (updated > 0) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    update incidents
                    set updated_at = ?, version = version + 1
                    where id = ? and tenant_id = ?
                    """)) {
            statement.setLong(1, now);
            statement.setString(2, incidentId);
            statement.setString(3, TenantContext.currentTenant());
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
        String targetStatus = archived ? "COMPLETED"
                : (message != null && message.contains("排队") ? "QUEUED" : "FAILED");
        return transitionCaseArchive(connection, incidentId, runId, targetStatus,
                documentId, message);
    }

    public boolean transitionCaseArchive(Connection connection,
                                         String incidentId,
                                         String runId,
                                         String targetStatus,
                                         String documentId,
                                         String message) throws SQLException {
        if (!java.util.Set.of("QUEUED", "RUNNING", "COMPLETED", "FAILED").contains(targetStatus)) {
            throw new IllegalArgumentException("非法的历史案例状态: " + targetStatus);
        }
        String stateGuard = switch (targetStatus) {
            case "QUEUED" -> " and case_archive_status in ('NOT_REQUESTED', 'FAILED', 'QUEUED')";
            case "RUNNING" -> " and case_archive_status in ('QUEUED', 'RUNNING')";
            case "COMPLETED" -> " and case_archive_status in ('NOT_REQUESTED', 'QUEUED', 'RUNNING', 'COMPLETED')";
            case "FAILED" -> " and case_archive_status in ('QUEUED', 'RUNNING', 'FAILED')";
            default -> throw new IllegalArgumentException("非法的历史案例状态: " + targetStatus);
        };
        String sql = """
                update diagnosis_runs
                set case_archived = ?,
                    case_archive_status = ?,
                    case_document_id = ?,
                    case_archive_message = ?,
                    version = version + 1
                where tenant_id = ? and incident_id = ? and run_id = ?
                """ + stateGuard;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, "COMPLETED".equals(targetStatus));
            statement.setString(2, targetStatus);
            statement.setString(3, documentId);
            statement.setString(4, message);
            statement.setString(5, TenantContext.currentTenant());
            statement.setString(6, incidentId);
            statement.setString(7, runId);
            return statement.executeUpdate() > 0;
        }
    }

    public Optional<DiagnosisRunRecord> findById(Connection connection,
                                                 String incidentId,
                                                 String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select * from diagnosis_runs
                where tenant_id = ? and incident_id = ? and run_id = ?
                """)) {
            statement.setString(1, TenantContext.currentTenant());
            statement.setString(2, incidentId);
            statement.setString(3, runId);
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
                                   long now,
                                   DiagnosisRunbookPolicy.Progress runbookProgress) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                update diagnosis_runs
                set status = ?,
                    started_at = case when started_at is null or started_at = 0 then ? else started_at end,
                    current_tool = ?,
                    runbook_id = ?,
                    runbook_status = ?,
                    runbook_step = ?,
                    runbook_required_tools = ?,
                    runbook_completed_tools = ?,
                    current_step = ?,
                    progress_message = ?,
                    version = version + 1
                where tenant_id = ?
                  and incident_id = ?
                  and run_id = ?
                  and version = ?
                  and status in ('QUEUED', 'RUNNING', 'WAITING_TOOL')
                """)) {
            statement.setString(1, status);
            statement.setLong(2, now);
            statement.setString(3, currentTool);
            bindRunbookProgress(statement, 4, runbookProgress);
            statement.setString(9, currentStep);
            statement.setString(10, progressMessage);
            statement.setString(11, TenantContext.currentTenant());
            statement.setString(12, incidentId);
            statement.setString(13, runId);
            statement.setLong(14, expectedVersion);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean appendToolEvidence(Connection connection,
                                      String incidentId,
                                      String runId,
                                      long expectedVersion,
                                      DiagnosisEvidence evidence,
                                      DiagnosisRunbookPolicy.Progress runbookProgress) throws Exception {
        if (!updateToolState(connection, incidentId, runId, expectedVersion,
                "RUNNING", null, "已完成工具调用 " + evidence.getToolName(), evidence.getSummary(),
                System.currentTimeMillis(), runbookProgress)) {
            return false;
        }
        evidenceRepository.insert(connection, runId, evidence);
        return true;
    }

    private void bindRunbookProgress(PreparedStatement statement,
                                     int index,
                                     DiagnosisRunbookPolicy.Progress progress) throws Exception {
        if (progress == null) {
            statement.setString(index++, null);
            statement.setString(index++, "NOT_STARTED");
            statement.setInt(index++, 0);
            statement.setString(index++, "[]");
            statement.setString(index, "[]");
            return;
        }
        statement.setString(index++, progress.runbookId());
        statement.setString(index++, progress.status());
        statement.setInt(index++, progress.completedStep());
        statement.setString(index++, objectMapper.writeValueAsString(progress.requiredTools()));
        statement.setString(index, objectMapper.writeValueAsString(progress.completedTools()));
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
                    runbook_status = case
                        when runbook_status = 'NOT_APPLICABLE' then runbook_status
                        else 'BLOCKED'
                    end,
                    version = version + 1
                where tenant_id = ?
                  and incident_id = ?
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
            statement.setString(4, TenantContext.currentTenant());
            statement.setString(5, incidentId);
            statement.setString(6, runId);
            statement.setLong(7, staleBeforeOrAt);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            return Optional.empty();
        }
        evidenceRepository.insert(connection, runId, evidence);
        try (PreparedStatement statement = connection.prepareStatement("""
                update incidents
                set updated_at = ?, version = version + 1
                where id = ? and tenant_id = ?
                """)) {
            statement.setLong(1, now);
            statement.setString(2, incidentId);
            statement.setString(3, TenantContext.currentTenant());
            statement.executeUpdate();
        }
        return findById(connection, incidentId, runId);
    }

    private void bind(PreparedStatement statement, DiagnosisRunRecord run) throws Exception {
        int index = 1;
        statement.setString(index++, TenantContext.currentTenant());
        statement.setString(index++, run.getRunId());
        statement.setString(index++, run.getIncidentId());
        statement.setString(index++, run.getStatus());
        statement.setLong(index++, run.getCreatedAt());
        index = bindAfterStatus(statement, index, run);
        statement.setLong(index, run.getVersion());
    }

    private void bindForUpdate(PreparedStatement statement, DiagnosisRunRecord run) throws Exception {
        int index = 1;
        statement.setString(index++, run.getStatus());
        index = bindAfterStatus(statement, index, run);
        statement.setString(index++, run.getRunId());
        statement.setLong(index++, run.getVersion());
        statement.setString(index, TenantContext.currentTenant());
    }

    private int bindAfterStatus(PreparedStatement statement, int index, DiagnosisRunRecord run) throws Exception {
        setNullableLong(statement, index++, run.getStartedAt());
        setNullableLong(statement, index++, run.getCompletedAt());
        statement.setString(index++, run.getAlertContext());
        statement.setString(index++, run.getReport());
        statement.setString(index++, run.getErrorMessage());
        statement.setString(index++, run.getCurrentStep());
        statement.setString(index++, run.getProgressMessage());
        statement.setString(index++, run.getCurrentTool());
        statement.setString(index++, run.getRunbookId());
        statement.setString(index++, run.getRunbookStatus());
        statement.setInt(index++, run.getRunbookStep());
        statement.setString(index++, objectMapper.writeValueAsString(run.getRunbookRequiredTools()));
        statement.setString(index++, objectMapper.writeValueAsString(run.getRunbookCompletedTools()));
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
        statement.setString(index++, run.getCaseArchiveStatus());
        statement.setString(index++, run.getCaseDocumentId());
        statement.setString(index++, run.getCaseArchiveMessage());
        return index;
    }

    private void insertRunRecord(Connection connection, DiagnosisRunRecord run) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into diagnosis_runs (
                    tenant_id, run_id, incident_id, status, created_at, started_at, completed_at,
                    alert_context, report, error_message, current_step, progress_message,
                    current_tool, runbook_id, runbook_status, runbook_step,
                    runbook_required_tools, runbook_completed_tools,
                    reused_from_run_id, reuse_reason, reuse_confidence,
                    reuse_validated_at, quality_score, quality_grade, quality_summary,
                    quality_issues, human_review_status, human_review_comment,
                    human_reviewed_at, case_archived, case_archive_status,
                    case_document_id, case_archive_message, version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, run);
            statement.executeUpdate();
        }
    }

    private DiagnosisRunRecord map(ResultSet resultSet) throws Exception {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setTenantId(resultSet.getString("tenant_id"));
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
        run.setRunbookId(resultSet.getString("runbook_id"));
        run.setRunbookStatus(resultSet.getString("runbook_status"));
        run.setRunbookStep(resultSet.getInt("runbook_step"));
        String requiredTools = resultSet.getString("runbook_required_tools");
        run.setRunbookRequiredTools(requiredTools == null
                ? List.of() : objectMapper.readValue(requiredTools, new TypeReference<List<String>>() { }));
        String completedTools = resultSet.getString("runbook_completed_tools");
        run.setRunbookCompletedTools(completedTools == null
                ? List.of() : objectMapper.readValue(completedTools, new TypeReference<List<String>>() { }));
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
        run.setCaseArchiveStatus(resultSet.getString("case_archive_status"));
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
