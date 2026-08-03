package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantContextTest {

    @Test
    void open_shouldRestorePreviousTenantAfterScopeCloses() {
        assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantContext.currentTenant());
        try (TenantContext.Scope ignored = TenantContext.open("tenant-a")) {
            assertEquals("tenant-a", TenantContext.currentTenant());
            try (TenantContext.Scope nested = TenantContext.open("tenant-b")) {
                assertEquals("tenant-b", TenantContext.currentTenant());
            }
            assertEquals("tenant-a", TenantContext.currentTenant());
        }
        assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantContext.currentTenant());
    }

    @Test
    void normalize_shouldRejectUnsafeTenantIds() {
        assertThrows(IllegalArgumentException.class, () -> TenantContext.normalize("tenant/id"));
    }

    @Test
    void requireMatch_shouldUsePersistedTenantAndRejectConflicts() {
        assertEquals("tenant-a", TenantContext.requireMatch("tenant-a", "tenant-a"));
        assertEquals("tenant-a", TenantContext.requireMatch("tenant-a", null));
        assertThrows(IllegalArgumentException.class,
                () -> TenantContext.requireMatch("tenant-a", "tenant-b"));
    }
}
