package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.AlertPayload;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class IncidentAlertRepository {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public IncidentAlertRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    public void append(Connection connection,
                       String incidentId,
                       String alertId,
                       AlertPayload payload,
                       long receivedAt) throws Exception {
        String rowId = alertId == null || alertId.isBlank()
                ? "ial-" + UUID.randomUUID().toString().substring(0, 12)
                : "ial-" + alertId;
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into incident_alerts (id, incident_id, alert_id, payload, received_at)
                values (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, rowId);
            statement.setString(2, incidentId);
            statement.setString(3, alertId);
            statement.setString(4, objectMapper.writeValueAsString(payload));
            statement.setLong(5, receivedAt);
            statement.executeUpdate();
        }
    }

    public void saveSnapshot(Connection connection,
                             String incidentId,
                             List<AlertPayload> payloads,
                             long receivedAt) throws Exception {
        if (payloads == null) {
            return;
        }
        for (int index = 0; index < payloads.size(); index++) {
            AlertPayload payload = payloads.get(index);
            String serializedPayload = objectMapper.writeValueAsString(payload);
            if (payloadExists(connection, incidentId, serializedPayload)) {
                continue;
            }
            String rowId = "ial-" + UUID.nameUUIDFromBytes(
                    (incidentId + "|" + index + "|" + serializedPayload)
                            .getBytes(StandardCharsets.UTF_8));
            try (PreparedStatement statement = connection.prepareStatement("""
                    merge into incident_alerts as target
                    using (values (?, ?, ?, ?, ?)) as source (
                        id, incident_id, alert_id, payload, received_at
                    )
                    on target.id = source.id
                    when matched then update set
                        payload = source.payload,
                        received_at = source.received_at
                    when not matched then insert (
                        id, incident_id, alert_id, payload, received_at
                    ) values (
                        source.id, source.incident_id, source.alert_id,
                        source.payload, source.received_at
                    )
                    """)) {
                statement.setString(1, rowId);
                statement.setString(2, incidentId);
                statement.setString(3, null);
                statement.setString(4, serializedPayload);
                statement.setLong(5, receivedAt + index);
                statement.executeUpdate();
            }
        }
    }

    private boolean payloadExists(Connection connection, String incidentId, String payload)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from incident_alerts where incident_id = ? and payload = ? limit 1")) {
            statement.setString(1, incidentId);
            statement.setString(2, payload);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public List<AlertPayload> findPayloadsByIncidentId(String incidentId) {
        List<AlertPayload> payloads = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select payload from incident_alerts
                     where incident_id = ?
                     order by received_at, id
                     """)) {
            statement.setString(1, incidentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    payloads.add(objectMapper.readValue(resultSet.getString("payload"), AlertPayload.class));
                }
            }
            return payloads;
        } catch (Exception e) {
            throw new IllegalStateException("读取 Incident 告警失败: " + incidentId, e);
        }
    }

    public void appendStoredAlert(String alertId, String incidentId,
                                  AlertPayload payload, long receivedAt) {
        try (Connection connection = dataSource.getConnection()) {
            append(connection, incidentId, alertId, payload, receivedAt);
        } catch (Exception e) {
            throw new IllegalStateException("保存告警失败: " + alertId, e);
        }
    }

    public List<StoredAlertRow> findStoredAlerts() {
        List<StoredAlertRow> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select alert_id, incident_id, payload, report, received_at
                     from incident_alerts
                     where alert_id is not null
                     order by received_at desc, alert_id
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(mapStoredAlert(resultSet));
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("读取告警列表失败", e);
        }
    }

    public StoredAlertRow findStoredAlert(String alertId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select alert_id, incident_id, payload, report, received_at
                     from incident_alerts where alert_id = ?
                     """)) {
            statement.setString(1, alertId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapStoredAlert(resultSet) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取告警失败: " + alertId, e);
        }
    }

    public void updateReport(String alertId, String report) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update incident_alerts set report = ? where alert_id = ?
                     """)) {
            statement.setString(1, report);
            statement.setString(2, alertId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("保存告警报告失败: " + alertId, e);
        }
    }

    public String findReport(String alertId) {
        StoredAlertRow row = findStoredAlert(alertId);
        return row == null ? null : row.report();
    }

    private StoredAlertRow mapStoredAlert(ResultSet resultSet) throws Exception {
        return new StoredAlertRow(
                resultSet.getString("alert_id"),
                resultSet.getString("incident_id"),
                objectMapper.readValue(resultSet.getString("payload"), AlertPayload.class),
                resultSet.getString("report"),
                resultSet.getLong("received_at")
        );
    }

    public record StoredAlertRow(String alertId, String incidentId, AlertPayload payload,
                                 String report, long receivedAt) {
    }
}
