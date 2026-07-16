package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.ResponseCookie;
import org.example.dto.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiSecurityInterceptor implements HandlerInterceptor {

    private static final java.security.SecureRandom CSRF_RANDOM = new java.security.SecureRandom();

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String WEBHOOK_SECRET_HEADER = "X-Webhook-Secret";
    private static final String SESSION_COOKIE = "SB_SESSION";
    private static final String CSRF_COOKIE = "SB_CSRF";
    private static final String CSRF_HEADER = "X-CSRF-Token";

    private AppSecurityProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ApiSecurityInterceptor(ObjectProvider<AppSecurityProperties> propertiesProvider) {
        this(propertiesProvider.getIfAvailable(AppSecurityProperties::new));
    }

    public ApiSecurityInterceptor(AppSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if ("/api/auth/login".equals(request.getRequestURI())) {
            return true;
        }

        if (request.getRequestURI().startsWith("/api/webhook/")) {
            return validateSecret(response, request.getHeader(WEBHOOK_SECRET_HEADER), properties.getWebhookSecret());
        }

        String presentedApiKey = request.getHeader(API_KEY_HEADER);
        if (isValidSecret(presentedApiKey, properties.getApiToken())) {
            return true;
        }
        Cookie session = findCookie(request, SESSION_COOKIE);
        if (session != null && isValidSession(session.getValue())) {
            if (isUnsafeMethod(request)
                    && (!isSameOrigin(request) || !isValidCsrfToken(request))) {
                writeUnauthorized(response);
                return false;
            }
            return true;
        }
        writeUnauthorized(response);
        return false;
    }

    private boolean validateSecret(HttpServletResponse response, String presentedSecret, String expectedSecret)
            throws java.io.IOException {
        if (isValidSecret(presentedSecret, expectedSecret)) {
            return true;
        }
        writeUnauthorized(response);
        return false;
    }

    private boolean isValidSecret(String presentedSecret, String expectedSecret) {
        if (expectedSecret == null || expectedSecret.isBlank()
                || presentedSecret == null || presentedSecret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedSecret.getBytes(StandardCharsets.UTF_8),
                presentedSecret.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isValidSession(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2) {
            return false;
        }
        try {
            long expiresAt = Long.parseLong(parts[0]);
            if (expiresAt <= Instant.now().getEpochSecond()) {
                return false;
            }
            String expected = sign(parts[0]);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String createSessionValue() {
        long expiresAt = Instant.now().getEpochSecond() + Math.max(300, properties.getSessionTtlSeconds());
        String expires = Long.toString(expiresAt);
        return expires + "." + sign(expires);
    }

    public ResponseCookie expiredSessionCookie() {
        return ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true).secure(properties.isCookieSecure())
                .sameSite("Lax").path("/").maxAge(0).build();
    }

    public ResponseCookie sessionCookie(String value) {
        return ResponseCookie.from(SESSION_COOKIE, value)
                .httpOnly(true).secure(properties.isCookieSecure())
                .sameSite("Lax").path("/")
                .maxAge(Math.max(300, properties.getSessionTtlSeconds())).build();
    }

    public String createCsrfToken() {
        byte[] token = new byte[32];
        CSRF_RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public ResponseCookie csrfCookie(String value) {
        return ResponseCookie.from(CSRF_COOKIE, value)
                .httpOnly(false).secure(properties.isCookieSecure())
                .sameSite("Lax").path("/")
                .maxAge(Math.max(300, properties.getSessionTtlSeconds())).build();
    }

    public ResponseCookie expiredCsrfCookie() {
        return ResponseCookie.from(CSRF_COOKIE, "")
                .httpOnly(false).secure(properties.isCookieSecure())
                .sameSite("Lax").path("/").maxAge(0).build();
    }

    private String sign(String value) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    properties.getApiToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法创建认证会话", e);
        }
    }

    private Cookie findCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    private boolean isUnsafeMethod(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private boolean isSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            origin = request.getHeader("Referer");
        }
        if (origin == null || origin.isBlank()) {
            return true;
        }
        String expected = request.getScheme() + "://" + request.getServerName();
        if (request.getServerPort() != 80 && request.getServerPort() != 443) {
            expected += ":" + request.getServerPort();
        }
        return origin.equals(expected) || origin.startsWith(expected + "/");
    }

    private boolean isValidCsrfToken(HttpServletRequest request) {
        Cookie cookie = findCookie(request, CSRF_COOKIE);
        String header = request.getHeader(CSRF_HEADER);
        return cookie != null && header != null && !header.isBlank()
                && MessageDigest.isEqual(cookie.getValue().getBytes(StandardCharsets.UTF_8),
                header.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletResponse response) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        org.example.dto.ApiResponse<Void> body = ApiResponse.error(401, "Unauthorized");
        body.setRequestId(java.util.UUID.randomUUID().toString());
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
