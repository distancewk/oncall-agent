package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppSecurityProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Issues and validates short-lived, revocable machine credentials. */
@Service
public class MachineTokenService {

    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String TOKEN_KIND_ATTRIBUTE = "APP_AUTH_TOKEN_KIND";
    public static final String TOKEN_ID_ATTRIBUTE = "APP_AUTH_TOKEN_ID";
    public static final String TENANT_ID_ATTRIBUTE = TenantContext.TENANT_ID_ATTRIBUTE;

    private static final String TOKEN_PREFIX = "SBA1";
    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final String REDIS_PREFIX = "superbizagent:machine-token:revoked:";
    private static final int MAX_SUBJECT_LENGTH = 128;
    private static final int MAX_SCOPE_LENGTH = 64;
    private static final int MAX_SCOPES = 16;

    private final AppSecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final Supplier<StringRedisTemplate> redisSupplier;
    private final Map<String, Long> localRevocations = new ConcurrentHashMap<>();

    @Autowired
    public MachineTokenService(AppSecurityProperties properties,
                               ObjectMapper objectMapper,
                               ObjectProvider<StringRedisTemplate> redisProvider) {
        this(properties, objectMapper, redisProvider::getIfAvailable);
    }

    MachineTokenService(AppSecurityProperties properties,
                        ObjectMapper objectMapper,
                        StringRedisTemplate redisTemplate) {
        this(properties, objectMapper, () -> redisTemplate);
    }

    public MachineTokenService(AppSecurityProperties properties) {
        this(properties, new ObjectMapper(), () -> null);
    }

    private MachineTokenService(AppSecurityProperties properties,
                                ObjectMapper objectMapper,
                                Supplier<StringRedisTemplate> redisSupplier) {
        this.properties = properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.redisSupplier = redisSupplier;
    }

    public IssuedToken issue(String subject, String role, List<String> scopes, Long requestedTtlSeconds) {
        return issue(subject, role, scopes, properties.getDefaultTenantId(), requestedTtlSeconds);
    }

    public IssuedToken issue(String subject, String role, List<String> scopes,
                             String tenantId, Long requestedTtlSeconds) {
        if (!properties.isMachineTokenEnabled()) {
            throw new IllegalStateException("机器令牌未启用");
        }
        if (!isSafeSubject(subject)) {
            throw new IllegalArgumentException("机器令牌 subject 非法");
        }
        String normalizedRole = normalizeRole(role);
        List<String> normalizedScopes = normalizeScopes(scopes);
        String normalizedTenantId = TenantContext.normalize(tenantId);
        long configuredTtl = Math.max(1L, properties.getMachineTokenTtlSeconds());
        long requestedTtl = requestedTtlSeconds == null || requestedTtlSeconds <= 0L
                ? configuredTtl : requestedTtlSeconds;
        long ttl = Math.min(configuredTtl, requestedTtl);
        long issuedAt = Instant.now().getEpochSecond();
        TokenClaims claims = new TokenClaims(subject.trim(), normalizedRole, normalizedScopes,
                issuedAt, issuedAt + ttl, UUID.randomUUID().toString(), normalizedTenantId);
        return new IssuedToken(signClaims(claims), claims);
    }

    public java.util.Optional<TokenClaims> authenticate(String authorizationHeader) {
        if (!properties.isMachineTokenEnabled() || authorizationHeader == null
                || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return java.util.Optional.empty();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.length() > MAX_TOKEN_LENGTH) {
            return java.util.Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !TOKEN_PREFIX.equals(parts[0])) {
            return java.util.Optional.empty();
        }
        try {
            Map<String, Object> header = decodeMap(parts[1]);
            Map<String, Object> payload = decodeMap(parts[2]);
            if (!"HS256".equals(header.get("alg")) || !TOKEN_PREFIX.equals(header.get("typ"))) {
                return java.util.Optional.empty();
            }
            if (!matchesAnySecret(parts[1] + "." + parts[2], parts[3])) {
                return java.util.Optional.empty();
            }
            TokenClaims claims = claimsFrom(payload);
            long now = Instant.now().getEpochSecond();
            long skew = Math.max(0L, properties.getMachineTokenClockSkewSeconds());
            long maxLifetime = Math.max(1L, properties.getMachineTokenTtlSeconds()) + skew;
            if (claims.issuedAt() > now + skew || claims.expiresAt() <= now
                    || claims.expiresAt() - claims.issuedAt() > maxLifetime) {
                return java.util.Optional.empty();
            }
            if (isRevoked(claims)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(claims);
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    public boolean revoke(TokenClaims claims) {
        return claims != null && revoke(claims.tokenId(), claims.expiresAt());
    }

    public boolean revoke(String tokenId, long expiresAt) {
        if (!isValidTokenId(tokenId) || expiresAt <= Instant.now().getEpochSecond()) {
            return false;
        }
        long ttlSeconds = Math.max(1L, expiresAt - Instant.now().getEpochSecond());
        String key = REDIS_PREFIX + sha256(tokenId);
        StringRedisTemplate redis = redisSupplier.get();
        if (redis != null) {
            try {
                Boolean stored = redis.opsForValue().setIfAbsent(
                        key, "1", Duration.ofSeconds(ttlSeconds));
                return Boolean.TRUE.equals(stored);
            } catch (RuntimeException e) {
                if (properties.isMachineTokenRequireRedis()) {
                    return false;
                }
            }
        } else if (properties.isMachineTokenRequireRedis()) {
            return false;
        }
        purgeLocalRevocations();
        return localRevocations.putIfAbsent(key,
                Instant.now().plusSeconds(ttlSeconds).toEpochMilli()) == null;
    }

    public record IssuedToken(String value, TokenClaims claims) {
    }

    public record TokenClaims(String subject, String role, List<String> scopes,
                               long issuedAt, long expiresAt, String tokenId, String tenantId) {
        public TokenClaims(String subject, String role, List<String> scopes,
                           long issuedAt, long expiresAt, String tokenId) {
            this(subject, role, scopes, issuedAt, expiresAt, tokenId, TenantContext.DEFAULT_TENANT_ID);
        }

        public TokenClaims {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            tenantId = TenantContext.normalize(tenantId);
        }
    }

    private String signClaims(TokenClaims claims) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", TOKEN_PREFIX);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", claims.subject());
            payload.put("role", claims.role());
            payload.put("scope", claims.scopes());
            payload.put("iat", claims.issuedAt());
            payload.put("exp", claims.expiresAt());
            payload.put("jti", claims.tokenId());
            payload.put("tenant_id", claims.tenantId());
            String encodedHeader = encode(header);
            String encodedPayload = encode(payload);
            return TOKEN_PREFIX + "." + encodedHeader + "." + encodedPayload + "."
                    + hmac(encodedHeader + "." + encodedPayload,
                    properties.getMachineTokenSigningSecretCandidates().get(0));
        } catch (Exception e) {
            throw new IllegalStateException("无法创建机器令牌", e);
        }
    }

    private Map<String, Object> decodeMap(String encoded) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            Map<String, Object> value = objectMapper.readValue(decoded,
                    new TypeReference<Map<String, Object>>() { });
            return value == null ? Map.of() : value;
        } catch (Exception e) {
            throw new IllegalArgumentException("机器令牌结构非法", e);
        }
    }

    private TokenClaims claimsFrom(Map<String, Object> payload) {
        String subject = stringValue(payload.get("sub"));
        String role = normalizeRole(stringValue(payload.get("role")));
        long issuedAt = longValue(payload.get("iat"));
        long expiresAt = longValue(payload.get("exp"));
        String tokenId = stringValue(payload.get("jti"));
        String tenantId = stringValue(payload.get("tenant_id"));
        if (!isSafeSubject(subject) || !isValidTokenId(tokenId) || expiresAt <= issuedAt) {
            throw new IllegalArgumentException("机器令牌声明非法");
        }
        Object rawScopes = payload.get("scope");
        if (!(rawScopes instanceof List<?>)) {
            throw new IllegalArgumentException("机器令牌 scope 非法");
        }
        List<String> scopes = new ArrayList<>();
        for (Object rawScope : (List<?>) rawScopes) {
            if (!(rawScope instanceof String scope)) {
                throw new IllegalArgumentException("机器令牌 scope 非法");
            }
            scopes.add(scope);
        }
        return new TokenClaims(subject, role, normalizeScopes(scopes), issuedAt, expiresAt,
                tokenId, tenantId);
    }

    private boolean matchesAnySecret(String input, String providedSignature) {
        if (providedSignature == null || providedSignature.isBlank()) {
            return false;
        }
        boolean matched = false;
        for (String secret : properties.getMachineTokenSigningSecretCandidates()) {
            matched |= MessageDigest.isEqual(
                    hmac(input, secret).getBytes(StandardCharsets.US_ASCII),
                    providedSignature.getBytes(StandardCharsets.US_ASCII));
        }
        return matched;
    }

    private String hmac(String input, String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算机器令牌签名", e);
        }
    }

    private String encode(Map<String, Object> value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                objectMapper.writeValueAsBytes(value));
    }

    private boolean isRevoked(TokenClaims claims) {
        String key = REDIS_PREFIX + sha256(claims.tokenId());
        StringRedisTemplate redis = redisSupplier.get();
        if (redis != null) {
            try {
                return Boolean.TRUE.equals(redis.hasKey(key));
            } catch (RuntimeException e) {
                return properties.isMachineTokenRequireRedis() || localRevocationExists(key);
            }
        }
        if (properties.isMachineTokenRequireRedis()) {
            return true;
        }
        return localRevocationExists(key);
    }

    private boolean localRevocationExists(String key) {
        purgeLocalRevocations();
        Long expiresAt = localRevocations.get(key);
        return expiresAt != null && expiresAt > System.currentTimeMillis();
    }

    private void purgeLocalRevocations() {
        long now = System.currentTimeMillis();
        localRevocations.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private List<String> normalizeScopes(List<String> scopes) {
        if (scopes == null) {
            return List.of();
        }
        if (scopes.size() > MAX_SCOPES) {
            throw new IllegalArgumentException("机器令牌 scope 数量过多");
        }
        List<String> normalized = scopes.stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        for (String scope : normalized) {
            if (scope.length() > MAX_SCOPE_LENGTH || !scope.matches("[a-z][a-z0-9:_-]*")) {
                throw new IllegalArgumentException("机器令牌 scope 非法");
            }
        }
        return Collections.unmodifiableList(normalized);
    }

    private String normalizeRole(String role) {
        if (!"ADMIN".equals(role) && !"OPERATOR".equals(role)) {
            throw new IllegalArgumentException("机器令牌 role 非法");
        }
        return role;
    }

    private String stringValue(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("机器令牌字段非法");
        }
        return text;
    }

    private long longValue(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("机器令牌时间字段非法");
        }
        return number.longValue();
    }

    private boolean isSafeSubject(String value) {
        return value != null && !value.isBlank() && value.trim().length() <= MAX_SUBJECT_LENGTH
                && value.trim().matches("[A-Za-z0-9._:@/-]+");
    }

    private boolean isValidTokenId(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成机器令牌撤销键", e);
        }
    }
}
