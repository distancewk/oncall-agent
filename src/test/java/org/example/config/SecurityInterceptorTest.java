package org.example.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.slf4j.MDC;
import jakarta.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityInterceptorTest {

    private ApiSecurityInterceptor interceptor;

    @BeforeEach
    void setUp() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setEnabled(true);
        properties.setApiToken("api-secret");
        properties.setWebhookSecret("webhook-secret");
        interceptor = new ApiSecurityInterceptor(properties);
    }

    @Test
    void preHandle_shouldRejectProtectedApiWithoutApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
        assertEquals(null, MDC.get("request_id"));
    }

    @Test
    void preHandle_shouldAllowProtectedApiWithMatchingApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.addHeader("X-API-Key", "api-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("X-Request-ID") != null);
    }

    @Test
    void preHandle_shouldProtectActuatorEndpointsWithTheSameApiBoundary() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());

        request.addHeader("X-API-Key", "api-secret");
        response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void preHandle_shouldAllowProtectedApiWithSignedHttpOnlySessionCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/sessions");
        request.setCookies(new Cookie("SB_SESSION", interceptor.createSessionValue()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void preHandle_shouldRequireMatchingCsrfTokenForCookieMutation() throws Exception {
        String csrf = interceptor.createCsrfToken();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.setCookies(new Cookie("SB_SESSION", interceptor.createSessionValue()),
                new Cookie("SB_CSRF", csrf));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());

        request.addHeader("X-CSRF-Token", csrf);
        response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void preHandle_shouldDelegateWebhookSignatureVerificationToController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/alert");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
        assertEquals("WEBHOOK", request.getAttribute(ApiSecurityInterceptor.ROLE_ATTRIBUTE));
    }

    @Test
    void preHandle_shouldNotBypassAuthForOtherWebhookRoutes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/other");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    void preHandle_shouldAllowRequestsWhenSecurityDisabled() throws Exception {
        ReflectionTestUtils.setField(interceptor, "properties", disabledProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/upload");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
    }

    @Test
    void preHandle_shouldSeparateOperatorAndAdminRolesWhenAdminTokenConfigured() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setEnabled(true);
        properties.setApiToken("operator-secret");
        properties.setAdminToken("admin-secret");
        interceptor = new ApiSecurityInterceptor(properties);
        Object endpoint = new AdminEndpoint();
        java.lang.reflect.Method method = AdminEndpoint.class.getMethod("adminOnly");
        Object handler = new org.springframework.web.method.HandlerMethod(endpoint, method);

        MockHttpServletRequest operatorRequest = new MockHttpServletRequest("GET", "/api/upload");
        operatorRequest.addHeader("X-API-Key", "operator-secret");
        MockHttpServletResponse operatorResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(operatorRequest, operatorResponse, handler));
        assertEquals(403, operatorResponse.getStatus());

        MockHttpServletRequest adminRequest = new MockHttpServletRequest("GET", "/api/upload");
        adminRequest.addHeader("X-API-Key", "admin-secret");
        MockHttpServletResponse adminResponse = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(adminRequest, adminResponse, handler));
        assertEquals("ADMIN", adminRequest.getAttribute(ApiSecurityInterceptor.ROLE_ATTRIBUTE));
    }

    @RequiresRole("ADMIN")
    static class AdminEndpoint {
        public void adminOnly() {
        }
    }

    private AppSecurityProperties disabledProperties() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setEnabled(false);
        return properties;
    }
}
