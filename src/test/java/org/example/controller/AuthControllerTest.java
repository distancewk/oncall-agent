package org.example.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.example.config.ApiSecurityInterceptor;
import org.example.config.AppSecurityProperties;
import org.example.service.MachineTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void oidcLogout_invalidatesBrowserSession() {
        AppSecurityProperties properties = new AppSecurityProperties();
        ApiSecurityInterceptor securityInterceptor = mock(ApiSecurityInterceptor.class);
        when(securityInterceptor.expiredSessionCookie())
                .thenReturn(ResponseCookie.from("SB_SESSION", "").build());
        when(securityInterceptor.expiredCsrfCookie())
                .thenReturn(ResponseCookie.from("SB_CSRF", "").build());
        AuthController controller = new AuthController(properties, securityInterceptor,
                mock(MachineTokenService.class));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession session = mock(HttpSession.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(mock(OidcUser.class));
        when(request.getSession(false)).thenReturn(session);

        assertEquals("logged-out", controller.logout(request, response, authentication)
                .getBody().getMessage());

        verify(session).invalidate();
    }
}
