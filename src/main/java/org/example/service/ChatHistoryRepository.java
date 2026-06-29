package org.example.service;

import org.example.dto.ChatSessionRecord;
import org.example.dto.ChatSessionSummary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ChatHistoryRepository {

    private final DataSource dataSource;

    public ChatHistoryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void appendMessagePair(String sessionId, long createTime, long updateTime,
                                  String title, String userQuestion, String aiAnswer) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertSession(connection, sessionId, title, createTime, updateTime);
                insertMessage(connection, sessionId, "user", userQuestion, updateTime);
                insertMessage(connection, sessionId, "assistant", aiAnswer, updateTime);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("追加聊天历史失败: " + sessionId, e);
        }
    }

    public void save(ChatSessionRecord record, String title) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertSession(connection, record.getSessionId(), title,
                        record.getCreateTime(), record.getUpdateTime());
                try (PreparedStatement statement = connection.prepareStatement(
                        "delete from chat_messages where session_id = ?")) {
                    statement.setString(1, record.getSessionId());
                    statement.executeUpdate();
                }
                long createdAt = record.getUpdateTime();
                for (Map<String, String> message : record.getMessageHistory()) {
                    insertMessage(connection, record.getSessionId(),
                            message.getOrDefault("role", ""),
                            message.getOrDefault("content", ""), createdAt++);
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存聊天历史失败: " + record.getSessionId(), e);
        }
    }

    public Optional<ChatSessionRecord> load(String sessionId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select session_id, created_at, updated_at
                     from chat_sessions where session_id = ?
                     """)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                ChatSessionRecord record = new ChatSessionRecord();
                record.setSessionId(resultSet.getString("session_id"));
                record.setCreateTime(resultSet.getLong("created_at"));
                record.setUpdateTime(resultSet.getLong("updated_at"));
                record.setMessageHistory(loadMessages(connection, sessionId));
                return Optional.of(record);
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取聊天历史失败: " + sessionId, e);
        }
    }

    public List<ChatSessionSummary> listSessions() {
        List<ChatSessionSummary> summaries = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select s.session_id, s.title, s.created_at, s.updated_at,
                            count(m.id) as message_count
                     from chat_sessions s
                     left join chat_messages m on m.session_id = s.session_id
                     group by s.session_id, s.title, s.created_at, s.updated_at
                     order by s.updated_at desc, s.session_id
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                ChatSessionSummary summary = new ChatSessionSummary();
                summary.setSessionId(resultSet.getString("session_id"));
                summary.setTitle(resultSet.getString("title"));
                summary.setMessagePairCount(resultSet.getInt("message_count") / 2);
                summary.setCreateTime(resultSet.getLong("created_at"));
                summary.setUpdateTime(resultSet.getLong("updated_at"));
                summaries.add(summary);
            }
            return summaries;
        } catch (Exception e) {
            throw new IllegalStateException("读取聊天历史列表失败", e);
        }
    }

    public boolean delete(String sessionId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "delete from chat_sessions where session_id = ?")) {
            statement.setString(1, sessionId);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("删除聊天历史失败: " + sessionId, e);
        }
    }

    private void upsertSession(Connection connection, String sessionId, String title,
                               long createTime, long updateTime) throws Exception {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                update chat_sessions
                set title = ?, updated_at = ?
                where session_id = ?
                """)) {
            statement.setString(1, title);
            statement.setLong(2, updateTime);
            statement.setString(3, sessionId);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into chat_sessions (session_id, title, created_at, updated_at)
                    values (?, ?, ?, ?)
                    """)) {
                statement.setString(1, sessionId);
                statement.setString(2, title);
                statement.setLong(3, createTime);
                statement.setLong(4, updateTime);
                statement.executeUpdate();
            }
        }
    }

    private void insertMessage(Connection connection, String sessionId, String role,
                               String content, long createdAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into chat_messages (session_id, role, content, created_at)
                values (?, ?, ?, ?)
                """)) {
            statement.setString(1, sessionId);
            statement.setString(2, role);
            statement.setString(3, content);
            statement.setLong(4, createdAt);
            statement.executeUpdate();
        }
    }

    private List<Map<String, String>> loadMessages(Connection connection, String sessionId)
            throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select role, content from chat_messages
                where session_id = ?
                order by id
                """)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, String> message = new LinkedHashMap<>();
                    message.put("role", resultSet.getString("role"));
                    message.put("content", resultSet.getString("content"));
                    messages.add(message);
                }
            }
        }
        return messages;
    }
}
