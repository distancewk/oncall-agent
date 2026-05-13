package org.example.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

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
    void preHandle_shouldRejectWebhookWithoutWebhookSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/alert");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    void preHandle_shouldAllowWebhookWithMatchingWebhookSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/alert");
        request.addHeader("X-Webhook-Secret", "webhook-secret");
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
