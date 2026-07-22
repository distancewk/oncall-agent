package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.AlertPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.annotation.PreDestroy;

@Service
public class AlertService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertService.class);

    private final IncidentAlertRepository repository;
    private final ObjectMapper objectMapper;
    private final List<AlertListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService listenerExecutor = new ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
            runnable -> {
                Thread thread = new Thread(runnable, "alert-listener-dispatch");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public AlertService(DataSource dataSource, ObjectMapper objectMapper,
                        IncidentSchemaMigrator schemaMigrator) {
        schemaMigrator.migrate();
        this.repository = new IncidentAlertRepository(dataSource, objectMapper);
        this.objectMapper = objectMapper;
    }

    public String storeAlert(AlertPayload payload) {
        return storeAlert(payload, null);
    }

    public String storeAlert(AlertPayload payload, String incidentId) {
        String alertId = AlertIdentity.id(payload, objectMapper);
        if (repository.findStoredAlert(alertId) != null) {
            return alertId;
        }
        long receivedAt = System.currentTimeMillis();
        repository.appendStoredAlert(alertId, incidentId, payload, receivedAt);
        StoredAlert storedAlert = toStoredAlert(
                new IncidentAlertRepository.StoredAlertRow(
                        alertId, incidentId, payload, null, receivedAt));
        LOGGER.info("告警已存储, alertId: {}, alert count: {}", alertId,
                payload.getAlerts() != null ? payload.getAlerts().size() : 0);
        notifyListeners(storedAlert);
        return alertId;
    }

    public List<StoredAlert> getAlerts() {
        return repository.findStoredAlerts().stream().map(this::toStoredAlert).toList();
    }

    public StoredAlert getAlert(String id) {
        IncidentAlertRepository.StoredAlertRow row = repository.findStoredAlert(id);
        return row == null ? null : toStoredAlert(row);
    }

    public void storeReport(String alertId, String report) {
        repository.updateReport(alertId, report);
        LOGGER.info("告警分析报告已存储, alertId: {}, report length: {}", alertId,
                report == null ? 0 : report.length());
    }

    public void storeMissingReportsForIncident(String incidentId, String report) {
        if (incidentId == null || incidentId.isBlank()) {
            return;
        }
        repository.updateMissingReportsByIncidentId(incidentId, report);
        LOGGER.info("Incident 下待关联告警报告已更新, incidentId: {}, report length: {}", incidentId,
                report == null ? 0 : report.length());
    }

    public String getReport(String alertId) {
        return repository.findReport(alertId);
    }

    public void addListener(AlertListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AlertListener listener) {
        listeners.remove(listener);
    }

    private StoredAlert toStoredAlert(IncidentAlertRepository.StoredAlertRow row) {
        AlertPayload payload = row.payload();
        StoredAlert storedAlert = new StoredAlert();
        storedAlert.setId(row.alertId());
        storedAlert.setIncidentId(row.incidentId());
        storedAlert.setStatus(payload.getStatus());
        storedAlert.setAlerts(payload.getAlerts());
        storedAlert.setGroupLabels(payload.getGroupLabels());
        storedAlert.setCommonLabels(payload.getCommonLabels());
        storedAlert.setCommonAnnotations(payload.getCommonAnnotations());
        storedAlert.setExternalURL(payload.getExternalURL());
        storedAlert.setReceivedAt(row.receivedAt());
        return storedAlert;
    }

    private void notifyListeners(StoredAlert alert) {
        for (AlertListener listener : listeners) {
            try {
                listenerExecutor.execute(() -> {
                    try {
                        listener.onAlert(alert);
                    } catch (Exception e) {
                        LOGGER.warn("通知告警监听器失败, type: {}", e.getClass().getSimpleName());
                    }
                });
            } catch (RejectedExecutionException e) {
                LOGGER.warn("告警监听器队列已满，跳过本次广播");
            }
        }
    }

    @PreDestroy
    void shutdownListenerExecutor() {
        listenerExecutor.shutdownNow();
    }

    public interface AlertListener {
        void onAlert(StoredAlert alert);
    }

    public static class StoredAlert {
        private String id;
        private String incidentId;
        private String status;
        private List<AlertPayload.Alert> alerts;
        private java.util.Map<String, String> groupLabels;
        private java.util.Map<String, String> commonLabels;
        private java.util.Map<String, String> commonAnnotations;
        private String externalURL;
        private long receivedAt;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getIncidentId() { return incidentId; }
        public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<AlertPayload.Alert> getAlerts() { return alerts; }
        public void setAlerts(List<AlertPayload.Alert> alerts) { this.alerts = alerts; }
        public java.util.Map<String, String> getGroupLabels() { return groupLabels; }
        public void setGroupLabels(java.util.Map<String, String> groupLabels) { this.groupLabels = groupLabels; }
        public java.util.Map<String, String> getCommonLabels() { return commonLabels; }
        public void setCommonLabels(java.util.Map<String, String> commonLabels) { this.commonLabels = commonLabels; }
        public java.util.Map<String, String> getCommonAnnotations() { return commonAnnotations; }
        public void setCommonAnnotations(java.util.Map<String, String> commonAnnotations) { this.commonAnnotations = commonAnnotations; }
        public String getExternalURL() { return externalURL; }
        public void setExternalURL(String externalURL) { this.externalURL = externalURL; }
        public long getReceivedAt() { return receivedAt; }
        public void setReceivedAt(long receivedAt) { this.receivedAt = receivedAt; }
    }
}
