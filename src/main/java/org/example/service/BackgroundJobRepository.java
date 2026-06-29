package org.example.service;

import org.example.dto.BackgroundJobRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BackgroundJobRepository {

    private final DataSource dataSource;

    public BackgroundJobRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Autowired
    public BackgroundJobRepository(DataSource dataSource, IncidentSchemaMigrator schemaMigrator) {
        schemaMigrator.migrate();
        this.dataSource = dataSource;
    }

    public BackgroundJobRecord enqueue(String jobType, String businessKey, String payload,
                                       int maxAttempts, long now) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                BackgroundJobRecord job = enqueue(
                        connection, jobType, businessKey, payload, maxAttempts, now);
                connection.commit();
                return job;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建后台任务失败: " + jobType + "/" + businessKey, e);
        }
    }

    public BackgroundJobRecord enqueue(Connection connection, String jobType, String businessKey,
                                       String payload, int maxAttempts, long now)
            throws SQLException {
        Optional<BackgroundJobRecord> existing =
                findByBusinessKey(connection, jobType, businessKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        String jobId = "job-" + UUID.randomUUID().toString().substring(0, 12);
        Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement("""
                     insert into background_jobs (
                         job_id, job_type, business_key, payload, status,
                         attempt_count, max_attempts, available_at, cancel_requested,
                         created_at, updated_at
                     ) values (?, ?, ?, ?, 'QUEUED', 0, ?, ?, false, ?, ?)
                     """)) {
            statement.setString(1, jobId);
            statement.setString(2, jobType);
            statement.setString(3, businessKey);
            statement.setString(4, payload == null ? "{}" : payload);
            statement.setInt(5, Math.max(1, maxAttempts));
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.setLong(8, now);
            statement.executeUpdate();
            return findById(connection, jobId).orElseThrow();
        } catch (SQLException e) {
            if (savepoint != null) {
                connection.rollback(savepoint);
            }
            if (isConstraintViolation(e)) {
                Optional<BackgroundJobRecord> repeated =
                        findByBusinessKey(connection, jobType, businessKey);
                if (repeated.isPresent()) {
                    return repeated.get();
                }
            }
            throw e;
        } finally {
            if (savepoint != null) {
                connection.releaseSavepoint(savepoint);
            }
        }
    }

    public Optional<BackgroundJobRecord> claimNext(String leaseOwner, long now,
                                                    long leaseDurationMillis) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean postgres = isPostgreSql(connection);
                String sql = postgres
                        ? """
                          select * from background_jobs
                          where status in ('QUEUED', 'RETRY')
                            and cancel_requested = false
                            and available_at <= ?
                          order by available_at, created_at, job_id
                          limit 1
                          for update skip locked
                          """
                        : """
                          select * from background_jobs
                          where status in ('QUEUED', 'RETRY')
                            and cancel_requested = false
                            and available_at <= ?
                          order by available_at, created_at, job_id
                          limit 1
                          for update
                          """;
                BackgroundJobRecord selected;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, now);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            connection.commit();
                            return Optional.empty();
                        }
                        selected = map(resultSet);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        update background_jobs
                        set status = 'RUNNING',
                            attempt_count = attempt_count + 1,
                            lease_owner = ?,
                            lease_expires_at = ?,
                            heartbeat_at = ?,
                            updated_at = ?
                        where job_id = ?
                          and status in ('QUEUED', 'RETRY')
                          and cancel_requested = false
                        """)) {
                    statement.setString(1, leaseOwner);
                    statement.setLong(2, now + Math.max(1L, leaseDurationMillis));
                    statement.setLong(3, now);
                    statement.setLong(4, now);
                    statement.setString(5, selected.getJobId());
                    if (statement.executeUpdate() == 0) {
                        connection.rollback();
                        return Optional.empty();
                    }
                }
                BackgroundJobRecord claimed = findById(connection, selected.getJobId()).orElseThrow();
                connection.commit();
                return Optional.of(claimed);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("领取后台任务失败", e);
        }
    }

    public boolean heartbeat(String jobId, String leaseOwner, long now, long leaseDurationMillis) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update background_jobs
                     set heartbeat_at = ?, lease_expires_at = ?, updated_at = ?
                     where job_id = ? and status = 'RUNNING' and lease_owner = ?
                     """)) {
            statement.setLong(1, now);
            statement.setLong(2, now + Math.max(1L, leaseDurationMillis));
            statement.setLong(3, now);
            statement.setString(4, jobId);
            statement.setString(5, leaseOwner);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("刷新后台任务租约失败: " + jobId, e);
        }
    }

    public void complete(String jobId, String leaseOwner, long now) {
        updateRunningTerminal(jobId, leaseOwner, "COMPLETED", null, now);
    }

    public void retryOrFail(String jobId, String leaseOwner, String error,
                            long retryAt, long now) {
        BackgroundJobRecord current = findById(jobId).orElseThrow();
        String status;
        long availableAt = retryAt;
        if (current.isCancelRequested()) {
            status = "CANCELLED";
            availableAt = current.getAvailableAt();
        } else if (current.getAttemptCount() >= current.getMaxAttempts()) {
            status = "FAILED";
            availableAt = current.getAvailableAt();
        } else {
            status = "RETRY";
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update background_jobs
                     set status = ?, available_at = ?, lease_owner = null,
                         lease_expires_at = null, heartbeat_at = null,
                         last_error = ?, updated_at = ?
                     where job_id = ? and status = 'RUNNING' and lease_owner = ?
                     """)) {
            statement.setString(1, status);
            statement.setLong(2, availableAt);
            statement.setString(3, error);
            statement.setLong(4, now);
            statement.setString(5, jobId);
            statement.setString(6, leaseOwner);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("结束失败后台任务失败: " + jobId, e);
        }
    }

    public boolean requestCancel(String jobType, String businessKey, long now) {
        try (Connection connection = dataSource.getConnection()) {
            return requestCancel(connection, jobType, businessKey, now);
        } catch (SQLException e) {
            throw new IllegalStateException("取消后台任务失败: " + jobType + "/" + businessKey, e);
        }
    }

    public boolean requestCancel(Connection connection, String jobType, String businessKey,
                                 long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                     update background_jobs
                     set cancel_requested = true,
                         status = case
                             when status in ('QUEUED', 'RETRY') then 'CANCELLED'
                             else status
                         end,
                         updated_at = ?
                     where job_type = ? and business_key = ?
                       and status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                     """)) {
            statement.setLong(1, now);
            statement.setString(2, jobType);
            statement.setString(3, businessKey);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean isCancelRequested(String jobId) {
        return findById(jobId).map(BackgroundJobRecord::isCancelRequested).orElse(true);
    }

    public int recoverExpiredLeases(long now) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update background_jobs
                     set status = case
                             when cancel_requested = true then 'CANCELLED'
                             when attempt_count >= max_attempts then 'FAILED'
                             else 'RETRY'
                         end,
                         available_at = ?,
                         lease_owner = null,
                         lease_expires_at = null,
                         heartbeat_at = null,
                         last_error = case
                             when cancel_requested = true then last_error
                             else '任务租约过期'
                         end,
                         updated_at = ?
                     where status = 'RUNNING'
                       and lease_expires_at is not null
                       and lease_expires_at < ?
                     """)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setLong(3, now);
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("恢复过期后台任务失败", e);
        }
    }

    public Optional<BackgroundJobRecord> findById(String jobId) {
        try (Connection connection = dataSource.getConnection()) {
            return findById(connection, jobId);
        } catch (Exception e) {
            throw new IllegalStateException("读取后台任务失败: " + jobId, e);
        }
    }

    public Optional<BackgroundJobRecord> findByBusinessKey(String jobType, String businessKey) {
        try (Connection connection = dataSource.getConnection()) {
            return findByBusinessKey(connection, jobType, businessKey);
        } catch (Exception e) {
            throw new IllegalStateException("读取后台任务失败: " + jobType + "/" + businessKey, e);
        }
    }

    private Optional<BackgroundJobRecord> findByBusinessKey(
            Connection connection, String jobType, String businessKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                     select * from background_jobs
                     where job_type = ? and business_key = ?
                     """)) {
            statement.setString(1, jobType);
            statement.setString(2, businessKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private void updateRunningTerminal(String jobId, String leaseOwner, String status,
                                       String error, long now) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update background_jobs
                     set status = case when cancel_requested = true then 'CANCELLED' else ? end,
                         lease_owner = null, lease_expires_at = null, heartbeat_at = null,
                         last_error = ?, updated_at = ?
                     where job_id = ? and status = 'RUNNING' and lease_owner = ?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, error);
            statement.setLong(3, now);
            statement.setString(4, jobId);
            statement.setString(5, leaseOwner);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("完成后台任务失败: " + jobId, e);
        }
    }

    private Optional<BackgroundJobRecord> findById(Connection connection, String jobId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select * from background_jobs where job_id = ?")) {
            statement.setString(1, jobId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private BackgroundJobRecord map(ResultSet resultSet) throws SQLException {
        BackgroundJobRecord job = new BackgroundJobRecord();
        job.setJobId(resultSet.getString("job_id"));
        job.setJobType(resultSet.getString("job_type"));
        job.setBusinessKey(resultSet.getString("business_key"));
        job.setPayload(resultSet.getString("payload"));
        job.setStatus(resultSet.getString("status"));
        job.setAttemptCount(resultSet.getInt("attempt_count"));
        job.setMaxAttempts(resultSet.getInt("max_attempts"));
        job.setAvailableAt(resultSet.getLong("available_at"));
        job.setLeaseOwner(resultSet.getString("lease_owner"));
        job.setLeaseExpiresAt(resultSet.getLong("lease_expires_at"));
        job.setHeartbeatAt(resultSet.getLong("heartbeat_at"));
        job.setCancelRequested(resultSet.getBoolean("cancel_requested"));
        job.setLastError(resultSet.getString("last_error"));
        job.setCreatedAt(resultSet.getLong("created_at"));
        job.setUpdatedAt(resultSet.getLong("updated_at"));
        return job;
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }

    private boolean isConstraintViolation(SQLException exception) {
        return exception.getSQLState() != null && exception.getSQLState().startsWith("23");
    }
}
