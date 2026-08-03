package org.example.config;

import org.example.service.MachineTokenService;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/** Applies resource scopes to machine tokens while preserving legacy credentials. */
public final class MachineTokenScopeAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    private final String requiredScope;

    private MachineTokenScopeAuthorizationManager(String requiredScope) {
        if (requiredScope == null || requiredScope.isBlank()) {
            throw new IllegalArgumentException("requiredScope 不能为空");
        }
        this.requiredScope = requiredScope;
    }

    public static MachineTokenScopeAuthorizationManager require(String scope) {
        return new MachineTokenScopeAuthorizationManager(scope);
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                        RequestAuthorizationContext context) {
        Authentication current = authentication == null ? null : authentication.get();
        if (current == null || !current.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }
        Object tokenKind = context.getRequest().getAttribute(MachineTokenService.TOKEN_KIND_ATTRIBUTE);
        if (!"machine".equals(tokenKind)) {
            return new AuthorizationDecision(true);
        }
        String authority = "SCOPE_" + requiredScope;
        boolean granted = current.getAuthorities().stream()
                .anyMatch(candidate -> authority.equals(candidate.getAuthority()));
        return new AuthorizationDecision(granted);
    }
}
