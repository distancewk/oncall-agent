package org.example.config;

import org.example.service.TenantContext;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.security")
public class AppSecurityProperties {

    private static final int MAX_WEBHOOK_SIGNING_SECRETS = 3;

    private boolean enabled;
    private String apiToken = "";
    private String adminToken = "";
    private String webhookSecret = "";
    private String webhookSigningSecret = "";
    /** Comma-separated secrets, newest first, for bounded rotation overlap. */
    private String webhookSigningSecrets = "";
    private String sessionSigningSecret = "";
    private boolean machineTokenEnabled;
    private String machineTokenSigningSecret = "";
    /** Comma-separated machine-token signing secrets, newest first. */
    private String machineTokenSigningSecrets = "";
    private long machineTokenTtlSeconds = 300;
    private long machineTokenClockSkewSeconds = 30;
    private boolean machineTokenRequireRedis;
    private long webhookMaxAgeSeconds = 300;
    private boolean webhookReplayRequireRedis;
    private String defaultTenantId = TenantContext.DEFAULT_TENANT_ID;
    private long sessionTtlSeconds = 28800;
    private boolean cookieSecure;
    private final Oidc oidc = new Oidc();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getWebhookSigningSecret() {
        return webhookSigningSecret == null || webhookSigningSecret.isBlank()
                ? webhookSecret : webhookSigningSecret;
    }

    public void setWebhookSigningSecret(String webhookSigningSecret) {
        this.webhookSigningSecret = webhookSigningSecret;
    }

    public String getWebhookSigningSecrets() {
        return webhookSigningSecrets;
    }

    public void setWebhookSigningSecrets(String webhookSigningSecrets) {
        this.webhookSigningSecrets = webhookSigningSecrets;
    }

    /** Returns active and overlap secrets in priority order. */
    public List<String> getWebhookSigningSecretCandidates() {
        if (webhookSigningSecrets == null || webhookSigningSecrets.isBlank()) {
            String legacy = getWebhookSigningSecret();
            return legacy == null || legacy.isBlank() ? List.of() : List.of(legacy);
        }
        return Arrays.stream(webhookSigningSecrets.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(MAX_WEBHOOK_SIGNING_SECRETS)
                .toList();
    }

    public String getSessionSigningSecret() {
        return sessionSigningSecret == null || sessionSigningSecret.isBlank()
                ? apiToken : sessionSigningSecret;
    }

    public void setSessionSigningSecret(String sessionSigningSecret) {
        this.sessionSigningSecret = sessionSigningSecret;
    }

    public boolean isMachineTokenEnabled() {
        return machineTokenEnabled;
    }

    public void setMachineTokenEnabled(boolean machineTokenEnabled) {
        this.machineTokenEnabled = machineTokenEnabled;
    }

    public String getMachineTokenSigningSecret() {
        return machineTokenSigningSecret == null || machineTokenSigningSecret.isBlank()
                ? apiToken : machineTokenSigningSecret;
    }

    public void setMachineTokenSigningSecret(String machineTokenSigningSecret) {
        this.machineTokenSigningSecret = machineTokenSigningSecret;
    }

    public String getMachineTokenSigningSecrets() {
        return machineTokenSigningSecrets;
    }

    public void setMachineTokenSigningSecrets(String machineTokenSigningSecrets) {
        this.machineTokenSigningSecrets = machineTokenSigningSecrets;
    }

    public List<String> getMachineTokenSigningSecretCandidates() {
        if (machineTokenSigningSecrets == null || machineTokenSigningSecrets.isBlank()) {
            String active = getMachineTokenSigningSecret();
            return active == null || active.isBlank() ? List.of() : List.of(active);
        }
        return Arrays.stream(machineTokenSigningSecrets.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(MAX_WEBHOOK_SIGNING_SECRETS)
                .toList();
    }

    public long getMachineTokenTtlSeconds() {
        return machineTokenTtlSeconds;
    }

    public void setMachineTokenTtlSeconds(long machineTokenTtlSeconds) {
        this.machineTokenTtlSeconds = machineTokenTtlSeconds;
    }

    public long getMachineTokenClockSkewSeconds() {
        return machineTokenClockSkewSeconds;
    }

    public void setMachineTokenClockSkewSeconds(long machineTokenClockSkewSeconds) {
        this.machineTokenClockSkewSeconds = machineTokenClockSkewSeconds;
    }

    public boolean isMachineTokenRequireRedis() {
        return machineTokenRequireRedis;
    }

    public void setMachineTokenRequireRedis(boolean machineTokenRequireRedis) {
        this.machineTokenRequireRedis = machineTokenRequireRedis;
    }

    public long getWebhookMaxAgeSeconds() {
        return webhookMaxAgeSeconds;
    }

    public void setWebhookMaxAgeSeconds(long webhookMaxAgeSeconds) {
        this.webhookMaxAgeSeconds = webhookMaxAgeSeconds;
    }

    public boolean isWebhookReplayRequireRedis() {
        return webhookReplayRequireRedis;
    }

    public void setWebhookReplayRequireRedis(boolean webhookReplayRequireRedis) {
        this.webhookReplayRequireRedis = webhookReplayRequireRedis;
    }

    public String getDefaultTenantId() {
        return TenantContext.normalize(defaultTenantId);
    }

    public void setDefaultTenantId(String defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public Oidc getOidc() {
        return oidc;
    }

    public static class Oidc {
        private boolean enabled;
        private String issuerUri = "";
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "{baseUrl}/login/oauth2/code/enterprise";
        private String registrationId = "enterprise";
        private String scopes = "openid,profile,email";
        private String tenantClaim = "tenant_id";
        private String groupsClaim = "groups";
        private String adminGroup = "ADMIN";
        private String operatorGroup = "OPERATOR";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public String getRegistrationId() {
            return registrationId;
        }

        public void setRegistrationId(String registrationId) {
            this.registrationId = registrationId;
        }

        public String getScopes() {
            return scopes;
        }

        public void setScopes(String scopes) {
            this.scopes = scopes;
        }

        public String getTenantClaim() {
            return tenantClaim;
        }

        public void setTenantClaim(String tenantClaim) {
            this.tenantClaim = tenantClaim;
        }

        public String getGroupsClaim() {
            return groupsClaim;
        }

        public void setGroupsClaim(String groupsClaim) {
            this.groupsClaim = groupsClaim;
        }

        public String getAdminGroup() {
            return adminGroup;
        }

        public void setAdminGroup(String adminGroup) {
            this.adminGroup = adminGroup;
        }

        public String getOperatorGroup() {
            return operatorGroup;
        }

        public void setOperatorGroup(String operatorGroup) {
            this.operatorGroup = operatorGroup;
        }
    }
}
