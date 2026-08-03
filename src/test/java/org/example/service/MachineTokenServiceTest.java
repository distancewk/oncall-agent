package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppSecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineTokenServiceTest {

    private AppSecurityProperties properties;
    private MachineTokenService service;

    @BeforeEach
    void setUp() {
        properties = new AppSecurityProperties();
        properties.setEnabled(true);
        properties.setMachineTokenEnabled(true);
        properties.setApiToken("api-secret");
        properties.setMachineTokenSigningSecret("machine-secret");
        properties.setMachineTokenTtlSeconds(300);
        service = new MachineTokenService(properties, new ObjectMapper(), (org.springframework.data.redis.core.StringRedisTemplate) null);
    }

    @Test
    void issueAndAuthenticate_shouldRoundTripClaimsAndScopes() {
        MachineTokenService.IssuedToken issued = service.issue(
                "worker-a", "OPERATOR", List.of("diagnosis:read"), 60L);

        Optional<MachineTokenService.TokenClaims> authenticated = service.authenticate(
                "Bearer " + issued.value());

        assertTrue(authenticated.isPresent());
        assertEquals("worker-a", authenticated.orElseThrow().subject());
        assertEquals("OPERATOR", authenticated.orElseThrow().role());
        assertEquals(List.of("diagnosis:read"), authenticated.orElseThrow().scopes());
        assertTrue(authenticated.orElseThrow().expiresAt() - authenticated.orElseThrow().issuedAt() <= 60L);
    }

    @Test
    void issueAndAuthenticate_shouldRoundTripSignedTenantClaim() {
        MachineTokenService.IssuedToken issued = service.issue(
                "worker-a", "OPERATOR", List.of("incidents:read"), "tenant-a", 60L);

        Optional<MachineTokenService.TokenClaims> authenticated = service.authenticate(
                "Bearer " + issued.value());

        assertEquals("tenant-a", authenticated.orElseThrow().tenantId());
    }

    @Test
    void authenticate_shouldRejectTamperingAndExpiredTokens() throws InterruptedException {
        MachineTokenService.IssuedToken issued = service.issue(
                "worker-a", "OPERATOR", List.of(), 60L);
        String tampered = issued.value().substring(0, issued.value().length() - 1) + "x";

        assertFalse(service.authenticate("Bearer " + tampered).isPresent());

        MachineTokenService.IssuedToken shortLived = service.issue(
                "worker-a", "OPERATOR", List.of(), 1L);
        Thread.sleep(1100L);
        assertFalse(service.authenticate("Bearer " + shortLived.value()).isPresent());
    }

    @Test
    void authenticate_shouldRejectOversizedTokenBeforeParsing() {
        String oversized = "Bearer SBA1." + "a".repeat(9000);

        assertTrue(service.authenticate(oversized).isEmpty());
    }

    @Test
    void revoke_shouldRejectFurtherAuthenticationUntilTokenExpires() {
        MachineTokenService.IssuedToken issued = service.issue(
                "worker-a", "OPERATOR", List.of(), 60L);

        assertTrue(service.revoke(issued.claims()));
        assertFalse(service.authenticate("Bearer " + issued.value()).isPresent());
    }

    @Test
    void authenticate_shouldAcceptPreviousSigningSecretDuringRotation() {
        properties.setMachineTokenSigningSecret("old-secret");
        MachineTokenService.IssuedToken oldToken = service.issue(
                "worker-a", "OPERATOR", List.of(), 60L);
        properties.setMachineTokenSigningSecret("new-secret");
        properties.setMachineTokenSigningSecrets("new-secret,old-secret");

        assertTrue(service.authenticate("Bearer " + oldToken.value()).isPresent());
    }

    @Test
    void authenticate_shouldFailClosedWhenRedisIsRequiredButUnavailable() {
        properties.setMachineTokenRequireRedis(true);
        MachineTokenService.IssuedToken issued = service.issue(
                "worker-a", "OPERATOR", List.of(), 60L);

        assertFalse(service.authenticate("Bearer " + issued.value()).isPresent());
        assertFalse(service.revoke(issued.claims()));
    }
}
