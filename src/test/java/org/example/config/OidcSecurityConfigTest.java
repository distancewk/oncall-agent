package org.example.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcSecurityConfigTest {

    private final OidcSecurityConfig config = new OidcSecurityConfig();

    @Test
    void mapUser_requiresTenantAndRecognizedRole() {
        AppSecurityProperties.Oidc properties = properties();
        OidcUser missingTenant = user(Map.of("sub", "subject", "groups", List.of("OPERATOR")));
        OidcUser unknownRole = user(Map.of("sub", "subject", "tenant_id", "tenant-a", "groups", List.of("VIEWER")));

        assertThrows(IllegalArgumentException.class, () -> config.mapUser(missingTenant, properties));
        assertThrows(IllegalArgumentException.class, () -> config.mapUser(unknownRole, properties));
    }

    @Test
    void mapUser_mapsSubjectTenantAndRoleClaims() {
        AppSecurityProperties.Oidc properties = properties();
        OidcUser mapped = config.mapUser(user(Map.of(
                "sub", "subject",
                "preferred_username", "operator@example.com",
                "tenant_id", "tenant-a",
                "groups", List.of("OPERATOR"))), properties);

        assertEquals("subject", mapped.getName());
        assertTrue(mapped.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        assertEquals("tenant-a", mapped.getClaimAsString("tenant_id"));
    }

    private AppSecurityProperties.Oidc properties() {
        AppSecurityProperties.Oidc properties = new AppSecurityProperties.Oidc();
        properties.setTenantClaim("tenant_id");
        properties.setGroupsClaim("groups");
        properties.setOperatorGroup("OPERATOR");
        properties.setAdminGroup("ADMIN");
        return properties;
    }

    private OidcUser user(Map<String, Object> claims) {
        Instant issuedAt = Instant.now().minusSeconds(5);
        OidcIdToken token = new OidcIdToken("id-token", issuedAt,
                issuedAt.plusSeconds(300), claims);
        return new DefaultOidcUser(List.of(), token,
                new OidcUserInfo(claims), "sub");
    }
}
