package org.example.service;

import org.example.config.AppSecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureVerifierTest {

    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setEnabled(true);
        properties.setWebhookSigningSecret("webhook-signing-secret");
        properties.setWebhookMaxAgeSeconds(300);
        verifier = new WebhookSignatureVerifier(properties);
    }

    @Test
    void verifyAndClaim_shouldAcceptValidSignatureOnlyOnce() {
        String body = "{\"status\":\"firing\"}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = "nonce-1";
        MockHttpServletRequest request = signedRequest(timestamp, nonce, body);

        assertTrue(verifier.verifyAndClaim(request, body));
        assertFalse(verifier.verifyAndClaim(signedRequest(timestamp, nonce, body), body));
    }

    @Test
    void verifyAndClaim_shouldRejectTamperedBodyAndExpiredTimestamp() {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = "nonce-2";
        String body = "{\"status\":\"firing\"}";
        MockHttpServletRequest request = signedRequest(timestamp, nonce, body);

        assertFalse(verifier.verifyAndClaim(request, body + " "));

        String expiredTimestamp = Long.toString(Instant.now().minusSeconds(301).getEpochSecond());
        assertFalse(verifier.verifyAndClaim(
                signedRequest(expiredTimestamp, "nonce-3", body), body));
    }

    @Test
    void verifyAndClaim_shouldRejectWhenRedisIsRequiredButUnavailable() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setEnabled(true);
        properties.setWebhookSigningSecret("webhook-signing-secret");
        properties.setWebhookReplayRequireRedis(true);
        WebhookSignatureVerifier strictVerifier = new WebhookSignatureVerifier(properties);
        String body = "{\"status\":\"firing\"}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = "nonce-redis-required";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/alert");
        request.addHeader(WebhookSignatureVerifier.TIMESTAMP_HEADER, timestamp);
        request.addHeader(WebhookSignatureVerifier.NONCE_HEADER, nonce);
        request.addHeader(WebhookSignatureVerifier.SIGNATURE_HEADER,
                strictVerifier.signature(timestamp, nonce, body));

        assertFalse(strictVerifier.verifyAndClaim(request, body));
    }

    @Test
    void verifyAndClaim_shouldAcceptPreviousSecretDuringRotationAndRevokeItWhenRemoved() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setEnabled(true);
        properties.setWebhookSigningSecrets("new-secret, old-secret");
        WebhookSignatureVerifier rotatingVerifier = new WebhookSignatureVerifier(properties);
        String body = "{\"status\":\"firing\"}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        MockHttpServletRequest oldRequest = new MockHttpServletRequest("POST", "/api/webhook/alert");
        oldRequest.addHeader(WebhookSignatureVerifier.TIMESTAMP_HEADER, timestamp);
        oldRequest.addHeader(WebhookSignatureVerifier.NONCE_HEADER, "nonce-old-key");
        oldRequest.addHeader(WebhookSignatureVerifier.SIGNATURE_HEADER,
                rotatingVerifier.signature(timestamp, "nonce-old-key", body, "old-secret"));

        assertTrue(rotatingVerifier.verifyAndClaim(oldRequest, body));

        properties.setWebhookSigningSecrets("new-secret");
        MockHttpServletRequest revokedRequest = new MockHttpServletRequest("POST", "/api/webhook/alert");
        revokedRequest.addHeader(WebhookSignatureVerifier.TIMESTAMP_HEADER, timestamp);
        revokedRequest.addHeader(WebhookSignatureVerifier.NONCE_HEADER, "nonce-old-key-revoked");
        revokedRequest.addHeader(WebhookSignatureVerifier.SIGNATURE_HEADER,
                rotatingVerifier.signature(timestamp, "nonce-old-key-revoked", body, "old-secret"));

        assertFalse(rotatingVerifier.verifyAndClaim(revokedRequest, body));
    }

    @Test
    void signingSecretCandidates_shouldBeBoundedAndDeduplicated() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setWebhookSigningSecrets("one, two, one, three, four");

        assertEquals(List.of("one", "two", "three"),
                properties.getWebhookSigningSecretCandidates());
    }

    private MockHttpServletRequest signedRequest(String timestamp, String nonce, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/alert");
        request.addHeader(WebhookSignatureVerifier.TIMESTAMP_HEADER, timestamp);
        request.addHeader(WebhookSignatureVerifier.NONCE_HEADER, nonce);
        request.addHeader(WebhookSignatureVerifier.SIGNATURE_HEADER,
                verifier.signature(timestamp, nonce, body));
        return request;
    }
}
