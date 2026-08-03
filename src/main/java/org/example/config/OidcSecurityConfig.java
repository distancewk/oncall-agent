package org.example.config;

import jakarta.servlet.http.HttpServletRequest;
import org.example.service.SecurityAuditService;
import org.example.service.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Arrays;

/** Optional enterprise OIDC login boundary for browser users. */
@Configuration
@ConditionalOnProperty(prefix = "app.security.oidc", name = "enabled", havingValue = "true")
public class OidcSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain oidcLoginFilterChain(HttpSecurity http,
                                             SecurityAuditService auditService) throws Exception {
        http.securityMatcher("/oauth2/**", "/login/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .oauth2Login(oauth2 -> oauth2.failureHandler((request, response, exception) -> {
                    recordAudit(auditService, request, "OIDC_LOGIN_FAILURE", "anonymous", null,
                            "DENIED", 401, "oauth2_authentication_failed");
                    response.sendError(401, "OIDC authentication failed");
                }))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        return http.build();
    }

    @Bean
    ClientRegistrationRepository oidcClientRegistrationRepository(AppSecurityProperties properties) {
        AppSecurityProperties.Oidc oidc = properties.getOidc();
        ClientRegistration.Builder registration = ClientRegistrations
                .fromIssuerLocation(oidc.getIssuerUri())
                .registrationId(oidc.getRegistrationId())
                .clientId(oidc.getClientId())
                .clientSecret(oidc.getClientSecret())
                .redirectUri(oidc.getRedirectUri())
                .scope(Arrays.stream(oidc.getScopes().split(","))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .toList());
        return new InMemoryClientRegistrationRepository(registration.build());
    }

    @Bean
    OidcUserService oidcUserService(AppSecurityProperties properties,
                                    SecurityAuditService auditService) {
        return new OidcUserService() {
            @Override
            public OidcUser loadUser(OidcUserRequest request) {
                OidcUser loaded = super.loadUser(request);
                try {
                    OidcUser mapped = mapUser(loaded, properties.getOidc());
                    recordAudit(auditService, null, "OIDC_LOGIN_SUCCESS", mapped.getName(),
                            mapped.getClaimAsString(properties.getOidc().getTenantClaim()),
                            "SUCCESS", 200, "claims_accepted");
                    return mapped;
                } catch (IllegalArgumentException e) {
                    String eventType = e.getMessage() != null
                            && e.getMessage().contains("tenant")
                            ? "OIDC_TENANT_REJECTED" : "OIDC_ROLE_REJECTED";
                    recordAudit(auditService, null, eventType, loaded.getName(), null,
                            "DENIED", 401, "claims_rejected");
                    throw new OAuth2AuthenticationException(
                            new OAuth2Error("invalid_token"), "OIDC claims rejected", e);
                }
            }
        };
    }

    private void recordAudit(SecurityAuditService auditService, HttpServletRequest request,
                             String eventType, String actor, String tenantId, String outcome,
                             int status, String detail) {
        String path = request == null ? "/login/oauth2/code/enterprise" : request.getRequestURI();
        String requestId = request == null ? null : value(request.getAttribute("APP_REQUEST_ID"));
        String traceId = request == null ? null
                : value(request.getAttribute(TraceExportService.TRACE_ID_ATTRIBUTE));
        auditService.record(SecurityAuditService.AuditEvent.request(
                eventType, actor, null, "oidc", null, "GET", path, outcome, status,
                requestId, traceId, detail,
                tenantId == null ? TenantContext.DEFAULT_TENANT_ID : tenantId));
    }

    private String value(Object value) {
        return value == null ? null : value.toString();
    }

    OidcUser mapUser(OidcUser user, AppSecurityProperties.Oidc properties) {
        String tenant = user.getClaimAsString(properties.getTenantClaim());
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("OIDC token 缺少 tenant_id claim");
        }
        TenantContext.normalize(tenant);
        List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
        List<String> groups = claimStrings(user, properties.getGroupsClaim());
        boolean admin = groups.stream().anyMatch(properties.getAdminGroup()::equalsIgnoreCase);
        boolean operator = groups.stream().anyMatch(properties.getOperatorGroup()::equalsIgnoreCase);
        if (!admin && !operator) {
            throw new IllegalArgumentException("OIDC token 缺少有效 role claim");
        }
        if (admin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        if (operator || admin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
        }
        // `sub` is the stable subject identifier.  The display name must not
        // accidentally be used as the claim-key selector by DefaultOidcUser.
        return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "sub");
    }

    private List<String> claimStrings(OidcUser user, String claimName) {
        Object value = user.getClaims().get(claimName);
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }
}
