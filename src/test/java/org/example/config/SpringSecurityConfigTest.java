package org.example.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WebMvcTest(controllers = {SpringSecurityConfigTest.ProtectedController.class,
        SpringSecurityConfigTest.IncidentProbeController.class},
        properties = "app.security.enabled=true")
@Import({SpringSecurityConfig.class, SpringSecurityConfigTest.ProtectedController.class,
        SpringSecurityConfigTest.IncidentProbeController.class,
        SpringSecurityConfigTest.TestSecurityProperties.class})
class SpringSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiSecurityInterceptor credentialVerifier;

    @MockBean
    private org.example.service.MachineTokenService machineTokenService;

    @MockBean
    private org.example.service.SecurityAuditService securityAuditService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        when(machineTokenService.authenticate(any())).thenReturn(Optional.empty());
    }

    @Test
    void protectedRequest_shouldRejectMissingCredential() throws Exception {
        when(credentialVerifier.authenticate(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/security-test"))
                .andExpect(status().isUnauthorized());
        verify(securityAuditService).record(any(org.example.service.SecurityAuditService.AuditEvent.class));
    }

    @Test
    void protectedRequest_shouldAcceptSpringSecurityAuthenticationFromApiKey() throws Exception {
        when(credentialVerifier.authenticate(any())).thenReturn(
                Optional.of(new ApiSecurityInterceptor.AuthenticationResult("OPERATOR", false)));

        mockMvc.perform(get("/api/security-test"))
                .andExpect(status().isOk());
    }

    @Test
    void operatorToken_shouldNotGainAdminRoleWhenAdminTokenIsMissing() throws Exception {
        when(credentialVerifier.authenticate(any())).thenReturn(
                Optional.of(new ApiSecurityInterceptor.AuthenticationResult("OPERATOR", false)));

        mockMvc.perform(get("/api/security-test/admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    void machineBearerToken_shouldCreateSpringSecurityAuthentication() throws Exception {
        when(machineTokenService.authenticate("Bearer machine-token")).thenReturn(
                Optional.of(new org.example.service.MachineTokenService.TokenClaims(
                        "worker-a", "OPERATOR", List.of("diagnosis:read"),
                        Instant.now().getEpochSecond(), Instant.now().plusSeconds(60).getEpochSecond(),
                        UUID.randomUUID().toString())));

        mockMvc.perform(get("/api/security-test")
                        .header("Authorization", "Bearer machine-token"))
                .andExpect(status().isOk());
    }

    @Test
    void machineBearerToken_shouldRequireScopeForIncidentReads() throws Exception {
        when(machineTokenService.authenticate("Bearer machine-token")).thenReturn(
                Optional.of(new org.example.service.MachineTokenService.TokenClaims(
                        "worker-a", "OPERATOR", List.of("diagnosis:read"),
                        Instant.now().getEpochSecond(), Instant.now().plusSeconds(60).getEpochSecond(),
                        UUID.randomUUID().toString())));

        mockMvc.perform(get("/api/incidents/incident-1")
                        .header("Authorization", "Bearer machine-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void machineBearerToken_shouldAllowIncidentReadsWithScope() throws Exception {
        when(machineTokenService.authenticate("Bearer machine-token")).thenReturn(
                Optional.of(new org.example.service.MachineTokenService.TokenClaims(
                        "worker-a", "OPERATOR", List.of("incidents:read"),
                        Instant.now().getEpochSecond(), Instant.now().plusSeconds(60).getEpochSecond(),
                        UUID.randomUUID().toString())));

        mockMvc.perform(get("/api/incidents/incident-1")
                        .header("Authorization", "Bearer machine-token"))
                .andExpect(status().isOk());
    }

    @Test
    void machineBearerToken_shouldRequireTriggerScopeForDiagnosisMutation() throws Exception {
        when(machineTokenService.authenticate("Bearer machine-token")).thenReturn(
                Optional.of(new org.example.service.MachineTokenService.TokenClaims(
                        "worker-a", "OPERATOR", List.of("incidents:read"),
                        Instant.now().getEpochSecond(), Instant.now().plusSeconds(60).getEpochSecond(),
                        UUID.randomUUID().toString())));

        mockMvc.perform(post("/api/incidents/incident-1/diagnose")
                        .header("Authorization", "Bearer machine-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyApiKey_shouldRetainIncidentReadCompatibilityWithoutScope() throws Exception {
        when(credentialVerifier.authenticate(any())).thenReturn(
                Optional.of(new ApiSecurityInterceptor.AuthenticationResult("OPERATOR", false)));

        mockMvc.perform(get("/api/incidents/incident-1")
                        .header("X-API-Key", "legacy-key"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidMachineBearer_shouldNotFallbackToStaticApiKey() throws Exception {
        when(credentialVerifier.authenticate(any())).thenReturn(
                Optional.of(new ApiSecurityInterceptor.AuthenticationResult("OPERATOR", false)));
        when(machineTokenService.authenticate("Bearer revoked-token")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/security-test")
                        .header("Authorization", "Bearer revoked-token")
                        .header("X-API-Key", "legacy-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionMutation_shouldRequireDoubleSubmitCsrfToken() throws Exception {
        when(credentialVerifier.authenticate(any())).thenReturn(
                Optional.of(new ApiSecurityInterceptor.AuthenticationResult("OPERATOR", true)));

        MvcResult rejected = mockMvc.perform(post("/api/security-test")
                        .cookie(new Cookie("SB_SESSION", "signed-session")))
                .andExpect(status().isForbidden())
                .andReturn();
        verify(securityAuditService).record(org.mockito.ArgumentMatchers.argThat(event ->
                "CSRF_DENIED".equals(event.eventType())
                        && "DENIED".equals(event.outcome())
                        && "session".equals(event.tokenKind())));
        String generatedToken = rejected.getResponse().getCookie("SB_CSRF").getValue();

        mockMvc.perform(post("/api/security-test")
                        .cookie(new Cookie("SB_SESSION", "signed-session"),
                                new Cookie("SB_CSRF", generatedToken))
                        .header("X-CSRF-Token", generatedToken))
                .andExpect(status().isOk());
    }

    @Test
    void csrfMatcher_shouldProtectOidcJsessionidMutations() {
        SpringSecurityConfig.SessionCookieRequestMatcher matcher =
                new SpringSecurityConfig.SessionCookieRequestMatcher();
        MockHttpServletRequest oidcMutation = new MockHttpServletRequest("POST", "/api/security-test");
        oidcMutation.setCookies(new Cookie("JSESSIONID", "oidc-session"));

        assertTrue(matcher.matches(oidcMutation));

        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/security-test");
        read.setCookies(new Cookie("JSESSIONID", "oidc-session"));
        assertFalse(matcher.matches(read));
    }

    @RestController
    @RequestMapping("/api/security-test")
    static class ProtectedController {

        @GetMapping
        String read() {
            return "ok";
        }

        @GetMapping("/admin")
        @PreAuthorize("hasRole('ADMIN')")
        String adminRead() {
            return "admin-ok";
        }

        @PostMapping
        String mutate() {
            return "ok";
        }
    }

    @RestController
    @RequestMapping("/api/incidents")
    static class IncidentProbeController {

        @GetMapping("/{incidentId}")
        String getIncident() {
            return "probe";
        }

        @PostMapping("/{incidentId}/diagnose")
        String diagnose() {
            return "probe";
        }
    }

    @TestConfiguration
    static class TestSecurityProperties {

        @Bean
        AppSecurityProperties appSecurityProperties() {
            AppSecurityProperties properties = new AppSecurityProperties();
            properties.setEnabled(true);
            return properties;
        }
    }
}
