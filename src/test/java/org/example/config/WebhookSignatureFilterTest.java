package org.example.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookSignatureFilterTest {

    private static final String SECRET = "webhook-secret";
    private static final String BODY = "{\"status\":\"firing\",\"alerts\":[]}";

    private AppSecurityProperties properties(boolean hmacRequired) {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setEnabled(true);
        properties.setWebhookSecret(SECRET);
        properties.setWebhookHmacRequired(hmacRequired);
        properties.setWebhookTimestampToleranceSeconds(300);
        return properties;
    }

    private StringRedisTemplate redisThatAcceptsNonce() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        return redis;
    }

    private StringRedisTemplate redisThatRejectsNonce() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        return redis;
    }

    @SuppressWarnings("unchecked")
    private WebhookSignatureFilter createFilter(AppSecurityProperties properties, StringRedisTemplate redis) {
        ObjectProvider<AppSecurityProperties> propsProvider = mock(ObjectProvider.class);
        when(propsProvider.getIfAvailable()).thenReturn(properties);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        return new WebhookSignatureFilter(propsProvider, redisProvider);
    }

    private static String hmac(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpServletRequest hmacRequest(String timestamp, String nonce, String body, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/alert");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader(WebhookSignatureFilter.TIMESTAMP_HEADER, timestamp);
        request.addHeader(WebhookSignatureFilter.NONCE_HEADER, nonce);
        request.addHeader(WebhookSignatureFilter.SIGNATURE_HEADER, signature);
        return request;
    }

    @Test
    void hmac_shouldPassWithValidSignature() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-1";
        String signature = hmac(SECRET, timestamp + "." + nonce + "." + BODY);

        WebhookSignatureFilter filter = createFilter(properties(true), redisThatAcceptsNonce());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(hmacRequest(timestamp, nonce, BODY, signature), response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void hmac_shouldRejectWhenSignatureIsWrong() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-2";

        WebhookSignatureFilter filter = createFilter(properties(true), redisThatAcceptsNonce());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(hmacRequest(timestamp, nonce, BODY, "deadbeef"), response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
    }

    @Test
    void hmac_shouldRejectWhenTimestampIsStale() throws Exception {
        String staleTimestamp = String.valueOf(Instant.now().getEpochSecond() - 3600);
        String nonce = "nonce-3";
        String signature = hmac(SECRET, staleTimestamp + "." + nonce + "." + BODY);

        WebhookSignatureFilter filter = createFilter(properties(true), redisThatAcceptsNonce());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(hmacRequest(staleTimestamp, nonce, BODY, signature), response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
    }

    @Test
    void hmac_shouldRejectReplayedNonce() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-replayed";
        String signature = hmac(SECRET, timestamp + "." + nonce + "." + BODY);

        WebhookSignatureFilter filter = createFilter(properties(true), redisThatRejectsNonce());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(hmacRequest(timestamp, nonce, BODY, signature), response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
    }

    @Test
    void sharedSecret_shouldPassWhenHmacNotRequired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/alert");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        request.addHeader(WebhookSignatureFilter.SECRET_HEADER, SECRET);

        WebhookSignatureFilter filter = createFilter(properties(false), null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void sharedSecret_shouldRejectWrongSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/alert");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        request.addHeader(WebhookSignatureFilter.SECRET_HEADER, "wrong-secret");

        WebhookSignatureFilter filter = createFilter(properties(false), null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
    }

    @Test
    void hmacRequired_shouldRejectWhenSignatureMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhook/alert");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        request.addHeader(WebhookSignatureFilter.SECRET_HEADER, SECRET);

        WebhookSignatureFilter filter = createFilter(properties(true), redisThatAcceptsNonce());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
    }
}
