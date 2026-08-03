package org.example.service;

/** Request/job-scoped tenant identity used by persistence and authorization boundaries. */
public final class TenantContext {

    public static final String DEFAULT_TENANT_ID = "default";
    public static final String TENANT_ID_ATTRIBUTE = "APP_TENANT_ID";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String currentTenant() {
        String tenant = CURRENT.get();
        return tenant == null ? DEFAULT_TENANT_ID : tenant;
    }

    public static Scope open(String tenantId) {
        String normalized = normalize(tenantId);
        String previous = CURRENT.get();
        CURRENT.set(normalized);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static String normalize(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return DEFAULT_TENANT_ID;
        }
        String normalized = tenantId.trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("tenantId 非法");
        }
        return normalized;
    }

    /** Uses the persisted job tenant as authority and rejects conflicting payload data. */
    public static String requireMatch(String persistedTenantId, String payloadTenantId) {
        String persisted = normalize(persistedTenantId);
        if (payloadTenantId != null && !payloadTenantId.isBlank()
                && !persisted.equals(normalize(payloadTenantId))) {
            throw new IllegalArgumentException("后台任务租户不一致");
        }
        return persisted;
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
