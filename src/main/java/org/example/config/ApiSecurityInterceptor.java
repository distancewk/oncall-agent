package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.example.dto.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Component
public class ApiSecurityInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiSecurityInterceptor.class);

    private static final java.security.SecureRandom CSRF_RANDOM = new java.security.SecureRandom();

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String SESSION_COOKIE = "SB_SESSION";
    private static final String CSRF_COOKIE = "SB_CSRF";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String REQUEST_ID_ATTRIBUTE = "APP_REQUEST_ID";
    private static final String TRACE_ID_ATTRIBUTE = "APP_TRACE_ID";
    private static final String SPAN_ID_ATTRIBUTE = "APP_SPAN_ID";
    public static final String ROLE_ATTRIBUTE = "APP_AUTH_ROLE";

    /** Authentication material extracted from the request before authorization. */
    public record AuthenticationResult(String role, boolean session) {
    }

    private AppSecurityProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TraceExportService traceExportService;

    @Autowired
    public ApiSecurityInterceptor(ObjectProvider<AppSecurityProperties> propertiesProvider,
                                 ObjectProvider<TraceExportService> traceExportProvider) {
        this(propertiesProvider.getIfAvailable(AppSecurityProperties::new),
                traceExportProvider.getIfAvailable());
    }

    public ApiSecurityInterceptor(AppSecurityProperties properties) {
        this(properties, null);
    }

    ApiSecurityInterceptor(AppSecurityProperties properties, TraceExportService traceExportService) {
        this.properties = properties;
        this.traceExportService = traceExportService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestId = requestCorrelationId(request);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(TraceExportService.START_NANOS_ATTRIBUTE, System.nanoTime());
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("request_id", requestId);
        TraceContext.Context traceContext = TraceContext.from(
                request.getHeader(TraceContext.TRACEPARENT_HEADER));
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceContext.traceId());
        request.setAttribute(SPAN_ID_ATTRIBUTE, traceContext.spanId());
        response.setHeader(TraceContext.TRACEPARENT_HEADER, traceContext.traceparent());
        MDC.put("trace_id", traceContext.traceId());
        MDC.put("span_id", traceContext.spanId());
        MDC.put("traceparent", traceContext.traceparent());
        if (!properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if ("/api/auth/login".equals(request.getRequestURI())) {
            return true;
        }

        if ("POST".equalsIgnoreCase(request.getMethod())
                && "/api/webhook/alert".equals(request.getRequestURI())) {
            // The webhook body signature and replay claim are verified by
            // WebhookController after the raw request body is available.
            request.setAttribute(ROLE_ATTRIBUTE, "WEBHOOK");
            return true;
        }

        String presentedApiKey = request.getHeader(API_KEY_HEADER);
        String role = roleForSecret(presentedApiKey);
        if (role != null) {
            request.setAttribute(ROLE_ATTRIBUTE, role);
            return authorizeHandler(request, response, handler, role);
        }
        Cookie session = findCookie(request, SESSION_COOKIE);
        if (session != null && isValidSession(session.getValue())) {
            if (isUnsafeMethod(request)
                    && (!isSameOrigin(request) || !isValidCsrfToken(request))) {
                writeUnauthorized(response);
                return rejectRequest(request);
            }
            role = sessionRole(session.getValue());
            request.setAttribute(ROLE_ATTRIBUTE, role);
            return authorizeHandler(request, response, handler, role);
        }
        writeUnauthorized(response);
        return rejectRequest(request);
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception exception) {
        if (traceExportService != null) {
            traceExportService.export(request, response, exception);
        }
        Object requestId = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (requestId != null && requestId.toString().equals(MDC.get("request_id"))) {
            MDC.remove("request_id");
        }
        MDC.remove("trace_id");
        MDC.remove("span_id");
        MDC.remove("traceparent");
    }

    /**
     * Extracts and verifies the existing API-key or signed-session credential.
     * Spring Security uses this method from its authentication filter; keeping
     * the verification rules here prevents the legacy compatibility interceptor
     * and the new filter chain from drifting apart.
     */
    public Optional<AuthenticationResult> authenticate(HttpServletRequest request) {
        String role = roleForSecret(request.getHeader(API_KEY_HEADER));
        if (role != null) {
            return Optional.of(new AuthenticationResult(role, false));
        }
        Cookie session = findCookie(request, SESSION_COOKIE);
        if (session != null && isValidSession(session.getValue())) {
            return Optional.of(new AuthenticationResult(sessionRole(session.getValue()), true));
        }
        return Optional.empty();
    }

    private String requestCorrelationId(HttpServletRequest request) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        if (supplied != null && supplied.matches("[A-Za-z0-9._:-]{1,64}")) {
            return supplied;
        }
        return java.util.UUID.randomUUID().toString();
    }

    private String roleForSecret(String presentedSecret) {
        if (properties.getAdminToken() != null && !properties.getAdminToken().isBlank()
                && isValidSecret(presentedSecret, properties.getAdminToken())) {
            return "ADMIN";
        }
        return isValidSecret(presentedSecret, properties.getApiToken()) ? "OPERATOR" : null;
    }

    private boolean authorizeHandler(HttpServletRequest request,
                                     HttpServletResponse response,
                                     Object handler,
                                     String role) throws java.io.IOException {
        RequiresRole requirement = null;
        if (handler instanceof HandlerMethod method) {
            requirement = method.getMethodAnnotation(RequiresRole.class);
            if (requirement == null) {
                requirement = method.getBeanType().getAnnotation(RequiresRole.class);
            }
        }
        if (requirement == null || hasRole(role, requirement.value())) {
            LOGGER.debug("认证请求通过, role={}, method={}, uri={}", role,
                    request.getMethod(), request.getRequestURI());
            return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(403, "Forbidden")));
        response.getWriter().flush();
        return rejectRequest(request);
    }

    private boolean rejectRequest(HttpServletRequest request) {
        MDC.remove("request_id");
        MDC.remove("trace_id");
        MDC.remove("span_id");
        MDC.remove("traceparent");
        return false;
    }

    private boolean hasRole(String actual, String required) {
        if ("ADMIN".equals(actual)) {
            return true;
        }
        if ("OPERATOR".equals(required) && "OPERATOR".equals(actual)) {
            return true;
        }
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
        String[] parts = value.split("\\.");
        if (parts.length != 2 && parts.length != 3) {
            return false;
        }
        try {
            long expiresAt = Long.parseLong(parts[0]);
            if (expiresAt <= Instant.now().getEpochSecond()) {
                return false;
            }
            String signedValue = parts.length == 3 ? parts[0] + "." + parts[1] : parts[0];
            String expected = sign(signedValue);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    parts[parts.length - 1].getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String createSessionValue() {
        return createSessionValue("OPERATOR");
    }

    public String createSessionValue(String role) {
        long expiresAt = Instant.now().getEpochSecond() + Math.max(300, properties.getSessionTtlSeconds());
        String expires = Long.toString(expiresAt);
        String safeRole = "ADMIN".equals(role) ? "ADMIN" : "OPERATOR";
        String signedValue = expires + "." + safeRole;
        return signedValue + "." + sign(signedValue);
    }

    private String sessionRole(String value) {
        String[] parts = value.split("\\.");
        return parts.length == 3 && "ADMIN".equals(parts[1]) ? "ADMIN" : "OPERATOR";
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
                    properties.getSessionSigningSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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
