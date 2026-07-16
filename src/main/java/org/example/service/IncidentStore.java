package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppIncidentProperties;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class IncidentStore {

    @FunctionalInterface
    public interface JdbcWork<T> {
        T execute(Connection connection) throws Exception;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(IncidentStore.class);
    private static final String FILE_SUFFIX = ".json";

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final IncidentSchemaMigrator schemaMigrator;
    private final DiagnosisRunRepository diagnosisRunRepository;
    private final IncidentAlertRepository incidentAlertRepository;
    private boolean jdbcInitialized;
    private boolean jdbcInitializing;

    public IncidentStore(AppIncidentProperties properties, ObjectMapper objectMapper, DataSource dataSource) {
        this(properties, objectMapper, dataSource, new IncidentSchemaMigrator(dataSource));
    }

    @Autowired
    public IncidentStore(AppIncidentProperties properties, ObjectMapper objectMapper, DataSource dataSource,
                         IncidentSchemaMigrator schemaMigrator) {
        this.baseDir = Path.of(properties.getPath());
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
        this.schemaMigrator = schemaMigrator;
        DiagnosisEvidenceRepository evidenceRepository = new DiagnosisEvidenceRepository(dataSource);
        this.diagnosisRunRepository = new DiagnosisRunRepository(dataSource, objectMapper, evidenceRepository);
        this.incidentAlertRepository = new IncidentAlertRepository(dataSource, objectMapper);
    }

    public void save(IncidentRecord record) {
        saveToJdbc(record);
    }

    public Optional<IncidentRecord> load(String incidentId) {
        validateIncidentId(incidentId);
        return loadFromJdbc(incidentId);
    }

    public List<IncidentRecord> list() {
        return listFromJdbc();
    }

    public List<IncidentRecord> listPage(int limit, int offset) {
        return listPage(limit, offset, null, null, null, null, null);
    }

    public List<IncidentRecord> listPage(int limit, int offset,
                                         String status, String severity, String query,
                                         String latestRunStatus, String humanReviewStatus) {
        ensureJdbcInitialized();
        int safeLimit = Math.max(1, Math.min(100, limit));
        int safeOffset = Math.max(0, offset);
        StringBuilder sql = new StringBuilder("""
                select id, aggregation_key, title, status, severity, alert_count,
                       version, created_at, updated_at, last_alert_at
                from incidents
                where 1 = 1
                """);
        List<Object> parameters = new ArrayList<>();
        if (notBlank(status)) {
            sql.append(" and lower(status) = lower(?)");
            parameters.add(status.trim());
        }
        if (notBlank(severity)) {
            sql.append(" and lower(severity) = lower(?)");
            parameters.add(severity.trim());
        }
        if (notBlank(query)) {
            sql.append(" and (lower(id) like lower(?) or lower(title) like lower(?) "
                    + "or lower(aggregation_key) like lower(?) or lower(severity) like lower(?) "
                    + "or lower(status) like lower(?))");
            String pattern = "%" + query.trim() + "%";
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
        }
        appendLatestRunFilter(sql, parameters, latestRunStatus, "status");
        appendLatestRunFilter(sql, parameters, humanReviewStatus, "human_review_status");
        sql.append(" order by updated_at desc, id limit ? offset ?");
        parameters.add(safeLimit);
        parameters.add(safeOffset);

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                Object parameter = parameters.get(index);
                if (parameter instanceof Integer integer) {
                    statement.setInt(index + 1, integer);
                } else {
                    statement.setString(index + 1, parameter.toString());
                }
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                ArrayList<IncidentRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(readIncidentRow(resultSet));
                }
                var runsByIncident = diagnosisRunRepository.findByIncidentIds(
                        connection, records.stream().map(IncidentRecord::getId).toList());
                for (IncidentRecord record : records) {
                    record.setDiagnosisRuns(runsByIncident.getOrDefault(record.getId(), List.of()));
                }
                return records;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 JDBC Incident 分页失败", e);
        }
    }

    private void appendLatestRunFilter(StringBuilder sql, List<Object> parameters,
                                       String expected, String column) {
        if (!notBlank(expected)) {
            return;
        }
        sql.append(" and exists (select 1 from diagnosis_runs latest "
                + "where latest.incident_id = incidents.id and lower(latest.")
                .append(column)
                .append(") = lower(?) and latest.run_id = (select candidate.run_id "
                        + "from diagnosis_runs candidate where candidate.incident_id = incidents.id "
                        + "order by candidate.created_at desc, candidate.run_id desc limit 1))");
        parameters.add(expected.trim());
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public List<StaleRunCandidate> findStaleRunCandidates(long cutoffMillis) {
        ensureJdbcInitialized();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select incident_id, run_id
                     from diagnosis_runs
                     where status in ('QUEUED', 'RUNNING', 'WAITING_TOOL')
                       and coalesce(started_at, created_at) < ?
                     order by coalesce(started_at, created_at), run_id
                     """)) {
            statement.setLong(1, cutoffMillis);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StaleRunCandidate> candidates = new ArrayList<>();
                while (resultSet.next()) {
                    candidates.add(new StaleRunCandidate(
                            resultSet.getString("incident_id"),
                            resultSet.getString("run_id")));
                }
                return candidates;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取超时诊断任务失败", e);
        }
    }

    public record StaleRunCandidate(String incidentId, String runId) {
    }

    public Optional<IncidentRecord> findByAggregationKey(String aggregationKey) {
        return findByAggregationKeyFromJdbc(aggregationKey);
    }

    public IncidentRecord recordAlert(IncidentRecord candidate, AlertPayload payload, long now) {
        return recordAlert(candidate, payload, now, null);
    }

    public IncidentRecord recordAlert(IncidentRecord candidate, AlertPayload payload,
                                      long now, String alertId) {
        return recordAlertWithStatus(candidate, payload, now, alertId).incident();
    }

    public RecordAlertResult recordAlertWithStatus(IncidentRecord candidate, AlertPayload payload,
                                                   long now, String alertId) {
        ensureJdbcInitialized();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    if (alertId != null) {
                        String existingIncidentId = findIncidentIdByAlertId(connection, alertId);
                        if (existingIncidentId != null) {
                            connection.rollback();
                            return new RecordAlertResult(load(existingIncidentId).orElseThrow(), false);
                        }
                    }
                    String incidentId = isPostgreSql(connection)
                            ? upsertPostgreSqlAlert(connection, candidate, now)
                            : upsertPortableAlert(connection, candidate, now);
                    incidentAlertRepository.append(connection, incidentId, alertId, payload, now);
                    connection.commit();
                    return new RecordAlertResult(load(incidentId).orElseThrow(), true);
                } catch (SQLException e) {
                    connection.rollback();
                    if (attempt < 3 && isConstraintViolation(e)) {
                        continue;
                    }
                    throw e;
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            } catch (Exception e) {
                throw new IllegalStateException("原子记录 Incident 告警失败: " + candidate.getAggregationKey(), e);
            }
        }
        throw new IllegalStateException("原子记录 Incident 告警重试耗尽: " + candidate.getAggregationKey());
    }

    public record RecordAlertResult(IncidentRecord incident, boolean created) {
    }

    private String findIncidentIdByAlertId(Connection connection, String alertId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select incident_id from incident_alerts where alert_id = ?")) {
            statement.setString(1, alertId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    public <T> T inTransaction(JdbcWork<T> work) {
        ensureJdbcInitialized();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                rollback(connection, e);
                throw new IllegalStateException("执行 JDBC IncidentStore 事务失败", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("打开 JDBC IncidentStore 事务失败", e);
        }
    }

    public void persistNewDiagnosisRun(Connection connection,
                                       String incidentId,
                                       DiagnosisRunRecord run,
                                       long updatedAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                update incidents
                set updated_at = ?, version = version + 1
                where id = ?
                """)) {
            statement.setLong(1, updatedAt);
            statement.setString(2, incidentId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Incident 不存在: " + incidentId);
            }
        }
        diagnosisRunRepository.insert(connection, run);
    }

    public long updateRunToolState(String incidentId,
                                   String runId,
                                   long expectedVersion,
                                   String status,
                                   String currentTool,
                                   String currentStep,
                                   String progressMessage) {
        return inTransaction(connection -> {
            long now = System.currentTimeMillis();
            boolean updated = diagnosisRunRepository.updateToolState(
                    connection, incidentId, runId, expectedVersion,
                    status, currentTool, currentStep, progressMessage, now);
            if (!updated) {
                return -1L;
            }
            touchIncident(connection, incidentId, now);
            return expectedVersion + 1L;
        });
    }

    public long appendToolEvidence(String incidentId,
                                   String runId,
                                   long expectedVersion,
                                   DiagnosisEvidence evidence) {
        return inTransaction(connection -> {
            boolean updated = diagnosisRunRepository.appendToolEvidence(
                    connection, incidentId, runId, expectedVersion, evidence);
            if (!updated) {
                return -1L;
            }
            touchIncident(connection, incidentId, System.currentTimeMillis());
            return expectedVersion + 1L;
        });
    }

    private void touchIncident(Connection connection, String incidentId, long updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update incidents
                set updated_at = ?, version = version + 1
                where id = ?
                """)) {
            statement.setLong(1, updatedAt);
            statement.setString(2, incidentId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Incident 不存在: " + incidentId);
            }
        }
    }

    public Optional<DiagnosisRunRecord> failStaleRunIfActive(String incidentId,
                                                             String runId,
                                                             long nowMillis,
                                                             long timeoutMillis,
                                                             DiagnosisEvidence evidence) {
        long staleBeforeOrAt = nowMillis - timeoutMillis;
        return inTransaction(connection -> diagnosisRunRepository.failActiveStaleRun(
                connection,
                incidentId,
                runId,
                staleBeforeOrAt,
                nowMillis,
                evidence.getContent(),
                evidence
        ));
    }

    private List<IncidentRecord> listFromFiles() {
        if (!Files.exists(baseDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(baseDir)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
                    .map(this::readIncident)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingLong(IncidentRecord::getUpdatedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取 Incident 列表失败", e);
        }
    }

    private Optional<IncidentRecord> readIncident(Path path) {
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), IncidentRecord.class));
        } catch (IOException e) {
            LOGGER.warn("跳过无法读取的 Incident 文件: {}", path, e);
            return Optional.empty();
        }
    }

    private synchronized void initializeJdbcStore() {
        try {
            if (jdbcInitialized || jdbcInitializing) {
                return;
            }
            jdbcInitializing = true;
            schemaMigrator.migrate();
            importExistingFileIncidentsIfJdbcEmpty();
            jdbcInitialized = true;
        } catch (SQLException | RuntimeException e) {
            jdbcInitialized = false;
            throw new IllegalStateException("初始化 JDBC IncidentStore 失败", e);
        } finally {
            jdbcInitializing = false;
        }
    }

    private void saveToJdbc(IncidentRecord record) {
        ensureJdbcInitialized();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                         merge into incidents as target
                         using (values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)) as source (
                             id, aggregation_key, title, status, severity, alert_count,
                             created_at, updated_at, last_alert_at, payload, version
                         )
                         on target.id = source.id
                         when matched and target.version = source.version then update set
                             aggregation_key = source.aggregation_key,
                             title = source.title,
                             status = source.status,
                             severity = source.severity,
                             alert_count = source.alert_count,
                             created_at = source.created_at,
                             updated_at = source.updated_at,
                             last_alert_at = source.last_alert_at,
                             payload = source.payload,
                             version = target.version + 1
                         when not matched then insert (
                             id, aggregation_key, title, status, severity, alert_count,
                             created_at, updated_at, last_alert_at, payload, version
                         ) values (
                             source.id, source.aggregation_key, source.title, source.status,
                             source.severity, source.alert_count, source.created_at,
                             source.updated_at, source.last_alert_at, source.payload, source.version
                         )
                         """)) {
                bindIncidentUpsert(statement, record);
                int affectedRows = statement.executeUpdate();
                try (PreparedStatement versionStatement = connection.prepareStatement(
                        "select version from incidents where id = ?")) {
                    versionStatement.setString(1, record.getId());
                    try (ResultSet resultSet = versionStatement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new IllegalStateException("保存 JDBC Incident 后未找到记录: " + record.getId());
                        }
                        long persistedVersion = resultSet.getLong(1);
                        boolean inserted = persistedVersion == record.getVersion();
                        boolean updated = persistedVersion == record.getVersion() + 1;
                        if (affectedRows == 0 || (!inserted && !updated)) {
                            throw new IllegalStateException("Incident 更新冲突: " + record.getId());
                        }
                        record.setVersion(persistedVersion);
                    }
                }
                incidentAlertRepository.saveSnapshot(
                        connection, record.getId(), record.getAlertPayloads(), record.getLastAlertAt());
                for (var run : record.getDiagnosisRuns()) {
                    diagnosisRunRepository.save(connection, run);
                }
                connection.commit();
            } catch (Exception e) {
                rollback(connection, e);
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存 JDBC Incident 失败: " + record.getId(), e);
        }
    }

    private Optional<IncidentRecord> loadFromJdbc(String incidentId) {
        ensureJdbcInitialized();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select id, aggregation_key, title, status, severity, alert_count,
                            version, created_at, updated_at, last_alert_at
                     from incidents where id = ?
                     """)) {
            statement.setString(1, incidentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(enrichNormalizedChildren(readIncidentRow(resultSet)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 JDBC Incident 失败: " + incidentId, e);
        }
    }

    private List<IncidentRecord> listFromJdbc() {
        ensureJdbcInitialized();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     select id, aggregation_key, title, status, severity, alert_count,
                            version, created_at, updated_at, last_alert_at
                     from incidents order by updated_at desc
                     """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<IncidentRecord> records = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    records.add(enrichNormalizedChildren(readIncidentRow(resultSet)));
                }
                return records;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 JDBC Incident 列表失败", e);
        }
    }

    private Optional<IncidentRecord> findByAggregationKeyFromJdbc(String aggregationKey) {
        ensureJdbcInitialized();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select id, aggregation_key, title, status, severity, alert_count,
                            version, created_at, updated_at, last_alert_at
                     from incidents
                     where aggregation_key = ?
                     order by updated_at desc
                     limit 1
                     """)) {
            statement.setString(1, aggregationKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(enrichNormalizedChildren(readIncidentRow(resultSet)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("按聚合键读取 JDBC Incident 失败: " + aggregationKey, e);
        }
    }

    private void bindIncidentUpsert(PreparedStatement statement, IncidentRecord record) throws Exception {
        statement.setString(1, record.getId());
        statement.setString(2, record.getAggregationKey());
        statement.setString(3, record.getTitle());
        statement.setString(4, record.getStatus());
        statement.setString(5, record.getSeverity());
        statement.setInt(6, record.getAlertCount());
        statement.setLong(7, record.getCreatedAt());
        statement.setLong(8, record.getUpdatedAt());
        statement.setLong(9, record.getLastAlertAt());
        statement.setString(10, "{}");
        statement.setLong(11, record.getVersion());
    }

    private IncidentRecord readIncidentPayload(String payload) throws IOException {
        return objectMapper.readValue(payload, IncidentRecord.class);
    }

    private IncidentRecord enrichNormalizedChildren(IncidentRecord record) {
        record.setAlertPayloads(incidentAlertRepository.findPayloadsByIncidentId(record.getId()));
        List<org.example.dto.DiagnosisRunRecord> normalizedRuns =
                diagnosisRunRepository.findByIncidentId(record.getId());
        record.setDiagnosisRuns(normalizedRuns);
        return record;
    }

    private IncidentRecord readIncidentRow(ResultSet resultSet) throws SQLException {
        IncidentRecord record = new IncidentRecord();
        record.setId(resultSet.getString("id"));
        record.setAggregationKey(resultSet.getString("aggregation_key"));
        record.setTitle(resultSet.getString("title"));
        record.setStatus(resultSet.getString("status"));
        record.setSeverity(resultSet.getString("severity"));
        record.setAlertCount(resultSet.getInt("alert_count"));
        record.setVersion(resultSet.getLong("version"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        record.setUpdatedAt(resultSet.getLong("updated_at"));
        record.setLastAlertAt(resultSet.getLong("last_alert_at"));
        return record;
    }

    private String upsertPostgreSqlAlert(Connection connection,
                                         IncidentRecord candidate,
                                         long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into incidents (
                    id, aggregation_key, title, status, severity, alert_count,
                    version, created_at, updated_at, last_alert_at, payload
                ) values (?, ?, ?, ?, ?, 1, 0, ?, ?, ?, '{}')
                on conflict (aggregation_key) do update set
                    title = excluded.title,
                    status = excluded.status,
                    severity = excluded.severity,
                    alert_count = incidents.alert_count + 1,
                    version = incidents.version + 1,
                    updated_at = excluded.updated_at,
                    last_alert_at = excluded.last_alert_at
                returning id
                """)) {
            statement.setString(1, candidate.getId());
            statement.setString(2, candidate.getAggregationKey());
            statement.setString(3, candidate.getTitle());
            statement.setString(4, candidate.getStatus());
            statement.setString(5, candidate.getSeverity());
            statement.setLong(6, candidate.getCreatedAt());
            statement.setLong(7, now);
            statement.setLong(8, now);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private String upsertPortableAlert(Connection connection,
                                       IncidentRecord candidate,
                                       long now) throws SQLException {
        String existingId = null;
        try (PreparedStatement select = connection.prepareStatement("""
                select id from incidents where aggregation_key = ? for update
                """)) {
            select.setString(1, candidate.getAggregationKey());
            try (ResultSet resultSet = select.executeQuery()) {
                if (resultSet.next()) {
                    existingId = resultSet.getString(1);
                }
            }
        }
        if (existingId != null) {
            try (PreparedStatement update = connection.prepareStatement("""
                    update incidents set
                        title = ?, status = ?, severity = ?,
                        alert_count = alert_count + 1,
                        version = version + 1,
                        updated_at = ?, last_alert_at = ?
                    where id = ?
                    """)) {
                update.setString(1, candidate.getTitle());
                update.setString(2, candidate.getStatus());
                update.setString(3, candidate.getSeverity());
                update.setLong(4, now);
                update.setLong(5, now);
                update.setString(6, existingId);
                update.executeUpdate();
            }
            return existingId;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into incidents (
                    id, aggregation_key, title, status, severity, alert_count,
                    version, created_at, updated_at, last_alert_at, payload
                ) values (?, ?, ?, ?, ?, 1, 0, ?, ?, ?, '{}')
                """)) {
            insert.setString(1, candidate.getId());
            insert.setString(2, candidate.getAggregationKey());
            insert.setString(3, candidate.getTitle());
            insert.setString(4, candidate.getStatus());
            insert.setString(5, candidate.getSeverity());
            insert.setLong(6, candidate.getCreatedAt());
            insert.setLong(7, now);
            insert.setLong(8, now);
            insert.executeUpdate();
            return candidate.getId();
        }
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }

    private boolean isConstraintViolation(SQLException error) {
        String state = error.getSQLState();
        return state != null && state.startsWith("23");
    }

    private void importExistingFileIncidentsIfJdbcEmpty() throws SQLException {
        if (jdbcIncidentCount() > 0) {
            return;
        }
        for (IncidentRecord record : listFromFiles()) {
            saveToJdbc(record);
        }
    }

    private int jdbcIncidentCount() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count(*) from incidents")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private Connection openConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            failure.addSuppressed(rollbackError);
        }
    }

    private void ensureJdbcInitialized() {
        if (!jdbcInitialized) {
            initializeJdbcStore();
        }
    }

    private void validateIncidentId(String incidentId) {
        if (incidentId == null || !incidentId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("非法 Incident ID: " + incidentId);
        }
    }

}
