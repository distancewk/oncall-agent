package org.example.service;

import org.example.dto.IndexTaskStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class IndexTaskRepository {

    private final DataSource dataSource;

    public IndexTaskRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void insert(IndexTaskStatus status) {
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, status);
        } catch (Exception e) {
            throw new IllegalStateException("创建索引任务失败: " + status.getTaskId(), e);
        }
    }

    public void insert(Connection connection, IndexTaskStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into index_tasks (
                    tenant_id, task_id, file_name, file_path, document_id, content_hash, status, message,
                    error_message, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, status);
            statement.executeUpdate();
        }
    }

    public void update(String taskId, String status, String message, String errorMessage,
                       long updatedAt) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update index_tasks
                     set status = ?, message = ?, error_message = ?, updated_at = ?
                     where tenant_id = ? and task_id = ?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, message);
            statement.setString(3, errorMessage);
            statement.setLong(4, updatedAt);
            statement.setString(5, TenantContext.currentTenant());
            statement.setString(6, taskId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("更新索引任务失败: " + taskId, e);
        }
    }

    public Optional<IndexTaskStatus> find(String taskId) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select * from index_tasks where tenant_id = ? and task_id = ?")) {
            statement.setString(1, TenantContext.currentTenant());
            statement.setString(2, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取索引任务失败: " + taskId, e);
        }
    }

    public List<IndexTaskStatus> list() {
        return list(200);
    }

    public List<IndexTaskStatus> list(int limit) {
        List<IndexTaskStatus> statuses = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from index_tasks
                     where tenant_id = ?
                     order by updated_at desc, task_id limit ?
                     """)) {
            statement.setString(1, TenantContext.currentTenant());
            statement.setInt(2, Math.max(1, Math.min(limit, 1000)));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    statuses.add(map(resultSet));
                }
                return statuses;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取索引任务列表失败", e);
        }
    }

    private void bind(PreparedStatement statement, IndexTaskStatus status) throws SQLException {
        statement.setString(1, TenantContext.currentTenant());
        statement.setString(2, status.getTaskId());
        statement.setString(3, status.getFileName());
        statement.setString(4, status.getFilePath());
        statement.setString(5, status.getDocumentId());
        statement.setString(6, status.getContentHash());
        statement.setString(7, status.getStatus());
        statement.setString(8, status.getMessage());
        statement.setString(9, status.getErrorMessage());
        statement.setLong(10, status.getCreatedAt());
        statement.setLong(11, status.getUpdatedAt());
    }

    private IndexTaskStatus map(ResultSet resultSet) throws Exception {
        IndexTaskStatus status = new IndexTaskStatus();
        status.setTenantId(resultSet.getString("tenant_id"));
        status.setTaskId(resultSet.getString("task_id"));
        status.setFileName(resultSet.getString("file_name"));
        status.setFilePath(resultSet.getString("file_path"));
        status.setDocumentId(resultSet.getString("document_id"));
        status.setContentHash(resultSet.getString("content_hash"));
        status.setStatus(resultSet.getString("status"));
        status.setMessage(resultSet.getString("message"));
        status.setErrorMessage(resultSet.getString("error_message"));
        status.setCreatedAt(resultSet.getLong("created_at"));
        status.setUpdatedAt(resultSet.getLong("updated_at"));
        return status;
    }
}
