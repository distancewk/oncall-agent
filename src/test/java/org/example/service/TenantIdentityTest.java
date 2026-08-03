package org.example.service;

import org.example.dto.AlertPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantIdentityTest {

    @Test
    void fromAlert_shouldPreferSignedCommonTenantLabel() {
        AlertPayload payload = new AlertPayload();
        payload.setGroupLabels(Map.of("tenant_id", "group-tenant"));
        payload.setCommonLabels(Map.of("tenant_id", "common-tenant"));

        assertEquals("common-tenant", TenantIdentity.fromAlert(payload));
    }

    @Test
    void fromAlert_shouldUseDefaultWhenTenantLabelIsAbsent() {
        assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantIdentity.fromAlert(new AlertPayload()));
    }

    @Test
    void fromAlert_shouldRejectUnsafeTenantLabel() {
        AlertPayload payload = new AlertPayload();
        payload.setCommonLabels(Map.of("tenant_id", "tenant/other"));

        assertThrows(IllegalArgumentException.class, () -> TenantIdentity.fromAlert(payload));
    }
}
