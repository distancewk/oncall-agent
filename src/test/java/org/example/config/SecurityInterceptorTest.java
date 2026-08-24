package org.example.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
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
    }

    @Test
    void preHandle_shouldAllowProtectedApiWithMatchingApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.addHeader("X-API-Key", "api-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
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
    void preHandle_shouldDelegateWebhookAuthToSignatureFilter() throws Exception {
        // Webhook 鉴权（HMAC 签名 + nonce 防重放 + 共享密钥回退）已由 WebhookSignatureFilter 统一接管，
        // interceptor 对 webhook 路径直接放行，不再做共享密钥校验。
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/alert");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
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

    private AppSecurityProperties disabledProperties() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setEnabled(false);
        return properties;
    }
}
