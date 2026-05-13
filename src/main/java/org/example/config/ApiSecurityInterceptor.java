package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.dto.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiSecurityInterceptor implements HandlerInterceptor {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String WEBHOOK_SECRET_HEADER = "X-Webhook-Secret";

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

        if (request.getRequestURI().startsWith("/api/webhook/")) {
            return validateSecret(response, request.getHeader(WEBHOOK_SECRET_HEADER), properties.getWebhookSecret());
        }

        return validateSecret(response, request.getHeader(API_KEY_HEADER), properties.getApiToken());
    }

    private boolean validateSecret(HttpServletResponse response, String presentedSecret, String expectedSecret)
            throws java.io.IOException {
        if (expectedSecret != null && !expectedSecret.isBlank() && expectedSecret.equals(presentedSecret)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(401, "Unauthorized")));
        response.getWriter().flush();
        return false;
    }
}
