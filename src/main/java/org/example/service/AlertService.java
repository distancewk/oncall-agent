package org.example.service;

import org.example.dto.AlertPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 告警管理服务
 * 管理告警历史和报告的内存存储
 */
@Service
public class AlertService {

    private static final Logger logger = LoggerFactory.getLogger(AlertService.class);

    private final Map<String, StoredAlert> alerts = new ConcurrentHashMap<>();
    private final Map<String, String> reports = new ConcurrentHashMap<>();
    private final List<AlertListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 存储告警
     *
     * @param payload Alertmanager Webhook 负载
     * @return 告警 ID
     */
    public String storeAlert(AlertPayload payload) {
        String alertId = UUID.randomUUID().toString().substring(0, 8);
        StoredAlert storedAlert = new StoredAlert();
        storedAlert.setId(alertId);
        storedAlert.setStatus(payload.getStatus());
        storedAlert.setAlerts(payload.getAlerts());
        storedAlert.setGroupLabels(payload.getGroupLabels());
        storedAlert.setCommonLabels(payload.getCommonLabels());
        storedAlert.setCommonAnnotations(payload.getCommonAnnotations());
        storedAlert.setExternalURL(payload.getExternalURL());
        storedAlert.setReceivedAt(System.currentTimeMillis());

        alerts.put(alertId, storedAlert);
        logger.info("告警已存储, alertId: {}, alert count: {}", alertId,
                payload.getAlerts() != null ? payload.getAlerts().size() : 0);

        // 通知所有监听器
        notifyListeners(storedAlert);

        return alertId;
    }

    /**
     * 获取所有告警列表
     */
    public List<StoredAlert> getAlerts() {
        List<StoredAlert> result = new ArrayList<>(alerts.values());
        result.sort((a, b) -> Long.compare(b.getReceivedAt(), a.getReceivedAt()));
        return result;
    }

    /**
     * 获取单个告警
     */
    public StoredAlert getAlert(String id) {
        return alerts.get(id);
    }

    /**
     * 存储分析报告
     */
    public void storeReport(String alertId, String report) {
        reports.put(alertId, report);
        logger.info("告警分析报告已存储, alertId: {}, report length: {}", alertId, report.length());
    }

    /**
     * 获取分析报告
     */
    public String getReport(String alertId) {
        return reports.get(alertId);
    }

    /**
     * 添加告警监听器（用于 SSE 推送）
     */
    public void addListener(AlertListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除告警监听器
     */
    public void removeListener(AlertListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(StoredAlert alert) {
        for (AlertListener listener : listeners) {
            try {
                listener.onAlert(alert);
            } catch (Exception e) {
                logger.warn("通知告警监听器失败", e);
            }
        }
    }

    /**
     * 告警监听器接口
     */
    public interface AlertListener {
        void onAlert(StoredAlert alert);
    }

    /**
     * 存储的告警信息
     */
    public static class StoredAlert {
        private String id;
        private String status;
        private List<AlertPayload.Alert> alerts;
        private Map<String, String> groupLabels;
        private Map<String, String> commonLabels;
        private Map<String, String> commonAnnotations;
        private String externalURL;
        private long receivedAt;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public List<AlertPayload.Alert> getAlerts() { return alerts; }
        public void setAlerts(List<AlertPayload.Alert> alerts) { this.alerts = alerts; }

        public Map<String, String> getGroupLabels() { return groupLabels; }
        public void setGroupLabels(Map<String, String> groupLabels) { this.groupLabels = groupLabels; }

        public Map<String, String> getCommonLabels() { return commonLabels; }
        public void setCommonLabels(Map<String, String> commonLabels) { this.commonLabels = commonLabels; }

        public Map<String, String> getCommonAnnotations() { return commonAnnotations; }
        public void setCommonAnnotations(Map<String, String> commonAnnotations) { this.commonAnnotations = commonAnnotations; }

        public String getExternalURL() { return externalURL; }
        public void setExternalURL(String externalURL) { this.externalURL = externalURL; }

        public long getReceivedAt() { return receivedAt; }
        public void setReceivedAt(long receivedAt) { this.receivedAt = receivedAt; }
    }
}
