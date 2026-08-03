package org.example.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.example.config.ApiSecurityInterceptor;
import org.example.config.AppSecurityProperties;
import org.example.dto.ApiResponse;
import org.example.service.MachineTokenService;
import org.example.service.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppSecurityProperties properties;
    private final ApiSecurityInterceptor securityInterceptor;
    private final MachineTokenService machineTokenService;

    public AuthController(AppSecurityProperties properties,
                          ApiSecurityInterceptor securityInterceptor,
                          MachineTokenService machineTokenService) {
        this.properties = properties;
        this.securityInterceptor = securityInterceptor;
        this.machineTokenService = machineTokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<String>> login(@RequestBody LoginRequest request,
                                                     HttpServletResponse response) {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.success("security-disabled"));
        }
        String role = roleFor(request == null ? null : request.apiKey());
        if (role == null) {
            ApiResponse<String> error = ApiResponse.error(401, "Unauthorized");
            error.setRequestId(java.util.UUID.randomUUID().toString());
            return ResponseEntity.status(401).body(error);
        }
        response.addHeader("Set-Cookie", securityInterceptor
                .sessionCookie(securityInterceptor.createSessionValue(role)).toString());
        response.addHeader("Set-Cookie", securityInterceptor
                .csrfCookie(securityInterceptor.createCsrfToken()).toString());
        return ResponseEntity.ok(ApiResponse.success("authenticated"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(HttpServletRequest request,
                                                     HttpServletResponse response,
                                                     Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser) {
            // The API endpoint is also the browser logout endpoint.  Expiring
            // the legacy SB_SESSION cookie alone would leave the OIDC
            // JSESSIONID-backed SecurityContext alive.
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            response.addHeader("Set-Cookie", ResponseCookie.from("JSESSIONID", "")
                    .path("/")
                    .maxAge(Duration.ZERO)
                    .httpOnly(true)
                    .secure(properties.isCookieSecure())
                    .sameSite("Lax")
                    .build().toString());
        }
        response.addHeader("Set-Cookie", securityInterceptor.expiredSessionCookie().toString());
        response.addHeader("Set-Cookie", securityInterceptor.expiredCsrfCookie().toString());
        return ResponseEntity.ok(ApiResponse.success("logged-out"));
    }

    @PostMapping("/token")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<MachineTokenResponse>> issueMachineToken(
            @RequestBody(required = false) MachineTokenRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        if (!properties.isEnabled() || !properties.isMachineTokenEnabled()
                || authentication == null || !authentication.isAuthenticated()
                || "machine".equals(httpRequest.getAttribute(MachineTokenService.TOKEN_KIND_ATTRIBUTE))) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "机器令牌签发未授权"));
        }
        String role = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))
                ? "ADMIN" : "OPERATOR";
        try {
            String requestedTenant = request == null ? null : request.tenantId();
            String tenantId = requestedTenant == null || requestedTenant.isBlank()
                    ? TenantContext.currentTenant() : TenantContext.normalize(requestedTenant);
            boolean admin = "ADMIN".equals(role);
            if (!admin && !TenantContext.currentTenant().equals(tenantId)) {
                return ResponseEntity.status(403).body(ApiResponse.error(403, "无权为其他租户签发令牌"));
            }
            MachineTokenService.IssuedToken issued = machineTokenService.issue(
                    authentication.getName(), role,
                    request == null ? List.of() : request.scopes(),
                    tenantId, request == null ? null : request.ttlSeconds());
            MachineTokenResponse response = new MachineTokenResponse(
                    issued.value(), "Bearer", issued.claims().expiresAt(), issued.claims().tokenId());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(ApiResponse.error(503, e.getMessage()));
        }
    }

    @PostMapping("/token/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> revokeMachineToken(
            @RequestBody RevokeMachineTokenRequest request,
            Authentication authentication) {
        if (!properties.isEnabled() || !properties.isMachineTokenEnabled()
                || authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "机器令牌撤销未授权"));
        }
        if (request == null || request.tokenId() == null || request.tokenId().isBlank()
                || request.expiresAt() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "tokenId 和 expiresAt 不能为空"));
        }
        if (!machineTokenService.revoke(request.tokenId(), request.expiresAt())) {
            return ResponseEntity.status(503).body(ApiResponse.error(503, "机器令牌撤销存储不可用或令牌已过期"));
        }
        return ResponseEntity.ok(ApiResponse.success("revoked"));
    }

    public record LoginRequest(String apiKey) {
    }

    public record MachineTokenRequest(List<String> scopes, Long ttlSeconds, String tenantId) {
        public MachineTokenRequest(List<String> scopes, Long ttlSeconds) {
            this(scopes, ttlSeconds, null);
        }
    }

    public record RevokeMachineTokenRequest(String tokenId, Long expiresAt) {
    }

    public record MachineTokenResponse(String accessToken, String tokenType,
                                       long expiresAt, String tokenId) {
    }

    private String roleFor(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        if (properties.getAdminToken() != null && !properties.getAdminToken().isBlank()
                && java.security.MessageDigest.isEqual(
                apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                properties.getAdminToken().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return "ADMIN";
        }
        return java.security.MessageDigest.isEqual(
                apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                properties.getApiToken().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                ? "OPERATOR" : null;
    }
}
