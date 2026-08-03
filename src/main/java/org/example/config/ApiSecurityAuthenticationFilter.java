package org.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.service.MachineTokenService;
import org.example.service.TenantContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the application's signed API-key/session contract into Spring
 * Security's request-scoped SecurityContext.
 */
public class ApiSecurityAuthenticationFilter extends OncePerRequestFilter {

    private final AppSecurityProperties properties;
    private final ApiSecurityInterceptor credentialVerifier;
    private final MachineTokenService machineTokenService;

    public ApiSecurityAuthenticationFilter(AppSecurityProperties properties,
                                           ApiSecurityInterceptor credentialVerifier,
                                           MachineTokenService machineTokenService) {
        this.properties = properties;
        this.credentialVerifier = credentialVerifier;
        this.machineTokenService = machineTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantId = properties.getDefaultTenantId();
        if (properties.isEnabled()) {
            String authorization = request.getHeader(MachineTokenService.AUTHORIZATION_HEADER);
            if (authorization != null && authorization.startsWith(MachineTokenService.BEARER_PREFIX)) {
                Optional<MachineTokenService.TokenClaims> claims = machineTokenService.authenticate(authorization);
                if (claims.isPresent()) {
                    MachineTokenService.TokenClaims tokenClaims = claims.get();
                    tenantId = tokenClaims.tenantId();
                    setAuthentication(request, tokenClaims.subject(), tokenClaims.role(),
                            "machine", tokenClaims.scopes(), tokenClaims.tokenId(), tenantId);
                } else {
                    // An invalid bearer must not fall through to a browser OIDC
                    // session that happens to be present on the same request.
                    SecurityContextHolder.clearContext();
                }
            } else {
                String legacyTenantId = tenantId;
                credentialVerifier.authenticate(request).ifPresent(result ->
                        setAuthentication(request, result.session() ? "session" : "api-key",
                                result.role(), result.session() ? "session" : "api-key", List.of(), null,
                                legacyTenantId));
            }
        }
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()
                && existing.getPrincipal() instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser oidcUser) {
            String oidcTenant = oidcUser.getClaimAsString(properties.getOidc().getTenantClaim());
            boolean validTenant = oidcTenant != null && !oidcTenant.isBlank();
            if (validTenant) {
                try {
                    tenantId = TenantContext.normalize(oidcTenant);
                } catch (IllegalArgumentException e) {
                    validTenant = false;
                }
            }
            boolean admin = validTenant && existing.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            boolean operator = validTenant && existing.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_OPERATOR".equals(a.getAuthority()));
            if (!validTenant || (!admin && !operator)) {
                // Do not turn an authenticated OIDC subject with an unknown or
                // missing role claim into an operator by default.  Clearing the
                // context makes the API chain return 401 for protected routes.
                SecurityContextHolder.clearContext();
                request.removeAttribute(ApiSecurityInterceptor.ROLE_ATTRIBUTE);
                request.removeAttribute(MachineTokenService.TOKEN_KIND_ATTRIBUTE);
            } else {
                request.setAttribute(ApiSecurityInterceptor.ROLE_ATTRIBUTE,
                        admin ? "ADMIN" : "OPERATOR");
                request.setAttribute(MachineTokenService.TOKEN_KIND_ATTRIBUTE, "oidc");
            }
        }
        request.setAttribute(TenantContext.TENANT_ID_ATTRIBUTE, tenantId);
        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            filterChain.doFilter(request, response);
        }
    }

    private void setAuthentication(HttpServletRequest request,
                                   String principal,
                                   String role,
                                   String tokenKind,
                                   List<String> scopes,
                                   String tokenId,
                                   String tenantId) {
        List<String> authorities = new ArrayList<>();
        authorities.add("ROLE_" + role);
        if (scopes != null) {
            scopes.stream().map(scope -> "SCOPE_" + scope).forEach(authorities::add);
        }
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, AuthorityUtils.createAuthorityList(
                                authorities.toArray(String[]::new)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(ApiSecurityInterceptor.ROLE_ATTRIBUTE, role);
        request.setAttribute(MachineTokenService.TOKEN_KIND_ATTRIBUTE, tokenKind);
        request.setAttribute(TenantContext.TENANT_ID_ATTRIBUTE, tenantId);
        if (tokenId != null) {
            request.setAttribute(MachineTokenService.TOKEN_ID_ATTRIBUTE, tokenId);
        }
    }
}
