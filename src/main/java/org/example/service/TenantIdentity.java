package org.example.service;

import org.example.dto.AlertPayload;

import java.util.Map;

/** Resolves the tenant carried by a signed monitoring webhook. */
public final class TenantIdentity {

    private TenantIdentity() {
    }

    public static String fromAlert(AlertPayload payload) {
        if (payload == null) {
            return TenantContext.DEFAULT_TENANT_ID;
        }
        String tenant = label(payload.getCommonLabels(), "tenant_id");
        if (tenant == null) {
            tenant = label(payload.getCommonLabels(), "tenant");
        }
        if (tenant == null) {
            tenant = label(payload.getGroupLabels(), "tenant_id");
        }
        if (tenant == null) {
            tenant = label(payload.getGroupLabels(), "tenant");
        }
        return TenantContext.normalize(tenant);
    }

    private static String label(Map<String, String> labels, String name) {
        if (labels == null || labels.get(name) == null || labels.get(name).isBlank()) {
            return null;
        }
        return labels.get(name).trim();
    }
}
