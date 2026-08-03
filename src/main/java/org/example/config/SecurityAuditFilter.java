package org.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.service.MachineTokenService;
import org.example.service.SecurityAuditService;
import org.example.service.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Records API security outcomes without persisting credentials or payloads. */
public class SecurityAuditFilter extends OncePerRequestFilter {

    private final SecurityAuditService auditService;

    public SecurityAuditFilter(SecurityAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        boolean checkedFailure = false;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            checkedFailure = true;
            throw e;
        } catch (RuntimeException e) {
            runtimeFailure = e;
            throw e;
        } catch (Error e) {
            errorFailure = e;
            throw e;
        } finally {
            int status = response.getStatus();
            String outcome = checkedFailure || errorFailure != null || runtimeFailure != null
                    ? "ERROR" : (status >= 400 ? "DENIED" : "SUCCESS");
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean authenticated = authentication != null && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getPrincipal());
            String actor = authenticated ? authentication.getName() : "anonymous";
            Object requestId = request.getAttribute("APP_REQUEST_ID");
            Object traceId = request.getAttribute(TraceExportService.TRACE_ID_ATTRIBUTE);
            Object role = request.getAttribute(ApiSecurityInterceptor.ROLE_ATTRIBUTE);
            Object tokenKind = request.getAttribute(MachineTokenService.TOKEN_KIND_ATTRIBUTE);
            Object tokenId = request.getAttribute(MachineTokenService.TOKEN_ID_ATTRIBUTE);
            Object tenantId = request.getAttribute(TenantContext.TENANT_ID_ATTRIBUTE);
            auditService.record(SecurityAuditService.AuditEvent.request(
                    classify(request.getMethod(), request.getRequestURI()),
                    actor,
                    role == null ? null : role.toString(),
                    tokenKind == null ? null : tokenKind.toString(),
                    tokenId == null ? null : tokenId.toString(),
                    request.getMethod(),
                    request.getRequestURI(),
                    outcome,
                    status,
                    requestId == null ? null : requestId.toString(),
                    traceId == null ? null : traceId.toString(),
                    "http_status=" + status,
                    tenantId == null ? TenantContext.currentTenant() : tenantId.toString()));
        }
    }

    private String classify(String method, String path) {
        if ("POST".equalsIgnoreCase(method)) {
            if (path.endsWith("/auth/login")) {
                return "AUTH_LOGIN";
            }
            if (path.endsWith("/auth/logout")) {
                return "AUTH_LOGOUT";
            }
            if (path.endsWith("/auth/token/revoke")) {
                return "MACHINE_TOKEN_REVOKE";
            }
            if (path.endsWith("/auth/token")) {
                return "MACHINE_TOKEN_ISSUE";
            }
            if (path.endsWith("/diagnose")) {
                return "DIAGNOSIS_TRIGGER";
            }
            if (path.endsWith("/cancel")) {
                return "DIAGNOSIS_CANCEL";
            }
            if (path.endsWith("/confirm")) {
                return "DIAGNOSIS_CONFIRM";
            }
            if (path.endsWith("/reject")) {
                return "DIAGNOSIS_REJECT";
            }
            if (path.endsWith("/archive-case")) {
                return "CASE_ARCHIVE";
            }
            if (path.endsWith("/upload")) {
                return "DOCUMENT_UPLOAD";
            }
            if (path.endsWith("/alert")) {
                return "WEBHOOK_ALERT";
            }
            return "API_MUTATION";
        }
        return "API_READ";
    }
}
