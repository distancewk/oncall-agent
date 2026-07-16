package org.example.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.config.ApiSecurityInterceptor;
import org.example.config.AppSecurityProperties;
import org.example.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppSecurityProperties properties;
    private final ApiSecurityInterceptor securityInterceptor;

    public AuthController(AppSecurityProperties properties,
                          ApiSecurityInterceptor securityInterceptor) {
        this.properties = properties;
        this.securityInterceptor = securityInterceptor;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<String>> login(@RequestBody LoginRequest request,
                                                     HttpServletResponse response) {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.success("security-disabled"));
        }
        if (request == null || request.apiKey() == null
            || !java.security.MessageDigest.isEqual(
                request.apiKey().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                properties.getApiToken().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            ApiResponse<String> error = ApiResponse.error(401, "Unauthorized");
            error.setRequestId(java.util.UUID.randomUUID().toString());
            return ResponseEntity.status(401).body(error);
        }
        response.addHeader("Set-Cookie", securityInterceptor
                .sessionCookie(securityInterceptor.createSessionValue()).toString());
        response.addHeader("Set-Cookie", securityInterceptor
                .csrfCookie(securityInterceptor.createCsrfToken()).toString());
        return ResponseEntity.ok(ApiResponse.success("authenticated"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(HttpServletResponse response) {
        response.addHeader("Set-Cookie", securityInterceptor.expiredSessionCookie().toString());
        response.addHeader("Set-Cookie", securityInterceptor.expiredCsrfCookie().toString());
        return ResponseEntity.ok(ApiResponse.success("logged-out"));
    }

    public record LoginRequest(String apiKey) {
    }
}
