package org.example.service;

import jakarta.servlet.http.HttpServletRequest;
import org.example.config.AppSecurityProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies signed webhook requests and claims a nonce before business processing.
 * Redis is used when configured so replay protection also works across instances;
 * the in-memory fallback keeps local development and unit tests deterministic.
 */
@Service
public class WebhookSignatureVerifier {

    public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";
    public static final String NONCE_HEADER = "X-Webhook-Nonce";
    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    private static final String REDIS_PREFIX = "superbizagent:webhook:nonce:";
    private static final int MAX_NONCE_LENGTH = 128;

    private final AppSecurityProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, Long> localClaims = new ConcurrentHashMap<>();

    public WebhookSignatureVerifier(AppSecurityProperties properties) {
        this(properties, (StringRedisTemplate) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookSignatureVerifier(AppSecurityProperties properties,
                                    ObjectProvider<StringRedisTemplate> redisProvider) {
        this(properties, redisProvider.getIfAvailable());
    }

    WebhookSignatureVerifier(AppSecurityProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    public boolean verifyAndClaim(HttpServletRequest request, String rawBody) {
        if (!properties.isEnabled()) {
            return true;
        }
        String timestampValue = request.getHeader(TIMESTAMP_HEADER);
        String nonce = request.getHeader(NONCE_HEADER);
        String providedSignature = request.getHeader(SIGNATURE_HEADER);
        if (timestampValue == null || nonce == null || providedSignature == null
                || nonce.isBlank() || nonce.length() > MAX_NONCE_LENGTH) {
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampValue);
        } catch (NumberFormatException e) {
            return false;
        }
        long maxAge = Math.max(1, properties.getWebhookMaxAgeSeconds());
        long now = Instant.now().getEpochSecond();
        if (timestamp < now - maxAge || timestamp > now + maxAge) {
            return false;
        }

        String normalizedProvided = providedSignature.startsWith("sha256=")
                ? providedSignature.substring("sha256=".length()) : providedSignature;
        if (!matchesAnySecret(timestampValue, nonce, rawBody == null ? "" : rawBody,
                normalizedProvided)) {
            return false;
        }
        return claimNonce(nonce, Duration.ofSeconds(maxAge));
    }

    public String signature(String timestamp, String nonce, String rawBody) {
        List<String> candidates = properties.getWebhookSigningSecretCandidates();
        return candidates.isEmpty() ? ""
                : signature(timestamp, nonce, rawBody == null ? "" : rawBody, candidates.get(0));
    }

    String signature(String timestamp, String nonce, String rawBody, String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + nonce + "." + rawBody)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 Webhook 签名", e);
        }
    }

    private boolean matchesAnySecret(String timestamp, String nonce, String rawBody,
                                     String providedSignature) {
        boolean matched = false;
        for (String secret : properties.getWebhookSigningSecretCandidates()) {
            String expected = signature(timestamp, nonce, rawBody, secret);
            // Evaluate every candidate so rotation does not reveal the matching key
            // through an early-exit timing difference.
            matched |= constantTimeEquals(expected, providedSignature);
        }
        return matched;
    }

    private boolean claimNonce(String nonce, Duration ttl) {
        String key = REDIS_PREFIX + sha256(nonce);
        if (redisTemplate != null) {
            try {
                Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
                return Boolean.TRUE.equals(claimed);
            } catch (RuntimeException ignored) {
                // A configured Redis replay store must fail closed. Falling back to
                // per-instance memory here would allow cross-instance replays.
                return false;
            }
        }
        if (properties.isWebhookReplayRequireRedis()) {
            return false;
        }
        long expiresAt = Instant.now().plus(ttl).toEpochMilli();
        localClaims.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());
        return localClaims.putIfAbsent(key, expiresAt) == null;
    }

    private boolean constantTimeEquals(String expected, String provided) {
        if (expected.isBlank() || provided == null || provided.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                provided.getBytes(StandardCharsets.US_ASCII));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                hex.append(String.format("%02x", current));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 Webhook nonce 键", e);
        }
    }
}
