package org.example.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.dto.ApiResponse;
import org.example.service.MachineTokenService;
import org.example.service.SecurityAuditService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Spring Security boundary for browser sessions, machine tokens and webhooks. */
@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class SpringSecurityConfig {

    private static final String SESSION_COOKIE = "SB_SESSION";
    private static final String OIDC_SESSION_COOKIE = "JSESSIONID";

    @Bean
    ApiSecurityAuthenticationFilter apiSecurityAuthenticationFilter(
            AppSecurityProperties properties,
            ApiSecurityInterceptor credentialVerifier,
            MachineTokenService machineTokenService) {
        return new ApiSecurityAuthenticationFilter(properties, credentialVerifier, machineTokenService);
    }

    @Bean
    SecurityAuditFilter securityAuditFilter(SecurityAuditService auditService) {
        return new SecurityAuditFilter(auditService);
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               AppSecurityProperties properties,
                                               ApiSecurityAuthenticationFilter authenticationFilter,
                                               SecurityAuditFilter securityAuditFilter,
                                               SecurityAuditService auditService,
                                               ObjectMapper objectMapper) throws Exception {
        http.securityMatcher("/api/**", "/actuator/**")
                .securityContext(context -> context.requireExplicitSave(false))
                .sessionManagement(session -> session.sessionCreationPolicy(
                        properties.getOidc().isEnabled()
                                ? SessionCreationPolicy.IF_REQUIRED : SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    if (!properties.isEnabled()) {
                        authorize.anyRequest().permitAll();
                    } else {
                        authorize.requestMatchers(
                                        "/api/auth/login",
                                        "/api/auth/logout",
                                        "/api/webhook/alert")
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/incidents/**")
                                .access(MachineTokenScopeAuthorizationManager.require("incidents:read"))
                                .requestMatchers(HttpMethod.POST, "/api/incidents/*/diagnose")
                                .access(MachineTokenScopeAuthorizationManager.require("diagnosis:trigger"))
                                .requestMatchers(HttpMethod.POST, "/api/incidents/*/runs/*/cancel")
                                .access(MachineTokenScopeAuthorizationManager.require("diagnosis:cancel"))
                                .requestMatchers(HttpMethod.POST, "/api/incidents/*/runs/*/confirm",
                                        "/api/incidents/*/runs/*/reject")
                                .access(MachineTokenScopeAuthorizationManager.require("diagnosis:review"))
                                .requestMatchers(HttpMethod.POST, "/api/incidents/*/archive-case")
                                .access(MachineTokenScopeAuthorizationManager.require("cases:write"))
                                .requestMatchers(HttpMethod.POST, "/api/upload")
                                .access(MachineTokenScopeAuthorizationManager.require("documents:write"))
                                .anyRequest().authenticated();
                    }
                })
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, error) ->
                                writeError(response, request, objectMapper, 401, "Unauthorized"))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(securityAuditFilter, ApiSecurityAuthenticationFilter.class);

        if (!properties.isEnabled()) {
            http.csrf(AbstractHttpConfigurer::disable);
        } else {
            http.csrf(csrf -> csrf
                    .csrfTokenRepository(csrfTokenRepository())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .requireCsrfProtectionMatcher(new SessionCookieRequestMatcher())
                    .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                        @Override
                        public <O extends CsrfFilter> O postProcess(O filter) {
                            filter.setAccessDeniedHandler(csrfAccessDeniedHandler(
                                    objectMapper, auditService));
                            return filter;
                        }
                    }));
        }
        return http.build();
    }

    private AccessDeniedHandler csrfAccessDeniedHandler(ObjectMapper objectMapper,
                                                        SecurityAuditService auditService) {
        return (request, response, error) -> {
            auditService.record(SecurityAuditService.AuditEvent.request(
                    "CSRF_DENIED",
                    "anonymous",
                    null,
                    "session",
                    null,
                    request.getMethod(),
                    request.getRequestURI(),
                    "DENIED",
                    403,
                    requestAttribute(request, "APP_REQUEST_ID"),
                    requestAttribute(request, TraceExportService.TRACE_ID_ATTRIBUTE),
                    "csrf_rejected"));
            accessDeniedHandler(objectMapper).handle(request, response, error);
        };
    }

    private String requestAttribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null ? null : value.toString();
    }

    private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, error) ->
                writeError(response, request, objectMapper, 403, "Forbidden");
    }

    private void writeError(HttpServletResponse response,
                            HttpServletRequest request,
                            ObjectMapper objectMapper,
                            int status,
                            String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Void> body = ApiResponse.error(status, message);
        Object requestId = request.getAttribute("APP_REQUEST_ID");
        if (requestId != null) {
            body.setRequestId(requestId.toString());
        }
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    private CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("SB_CSRF");
        repository.setHeaderName("X-CSRF-Token");
        return repository;
    }

    static final class SessionCookieRequestMatcher implements RequestMatcher {

        @Override
        public boolean matches(HttpServletRequest request) {
            if (!isUnsafe(request.getMethod())) {
                return false;
            }
            String uri = request.getRequestURI();
            if ("/api/auth/login".equals(uri) || "/api/webhook/alert".equals(uri)) {
                return false;
            }
            if (request.getCookies() == null) {
                return false;
            }
            for (Cookie cookie : request.getCookies()) {
                if ((SESSION_COOKIE.equals(cookie.getName())
                        || OIDC_SESSION_COOKIE.equals(cookie.getName()))
                        && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return true;
                }
            }
            return false;
        }

        private boolean isUnsafe(String method) {
            return "POST".equalsIgnoreCase(method)
                    || "PUT".equalsIgnoreCase(method)
                    || "PATCH".equalsIgnoreCase(method)
                    || "DELETE".equalsIgnoreCase(method);
        }
    }
}
