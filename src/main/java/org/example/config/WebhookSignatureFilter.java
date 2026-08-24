package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.example.dto.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Webhook 签名与防重放过滤器。
 *
 * <p>统一接管 {@code /api/webhook/**} 的鉴权，取代原先 {@link ApiSecurityInterceptor}
 * 中仅做 {@code X-Webhook-Secret} 头等值比较的逻辑。支持两种模式：</p>
 * <ul>
 *   <li><b>HMAC 签名模式</b>：请求头携带 {@code X-Webhook-Timestamp}（Unix 秒）、
 *       {@code X-Webhook-Nonce}（随机串）、{@code X-Webhook-Signature}（hex，HmacSHA256，
 *       签名内容为 {@code timestamp + "." + nonce + "." + rawBody}，密钥为 webhook-secret）。
 *       校验时间戳窗口、Redis 认领 nonce（防重放）与签名。Redis 不可用时 fail-closed。</li>
 *   <li><b>共享密钥兼容模式</b>（默认）：无签名头时回退到 {@code X-Webhook-Secret}
 *       头等值比较，兼容 Alertmanager 等只支持固定头的客户端。</li>
 * </ul>
 * <p>{@code app.security.webhook-hmac-required=true} 时强制 HMAC 模式，缺签名头直接拒绝。</p>
 */
@Component
public class WebhookSignatureFilter extends OncePerRequestFilter {

    static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";
    static final String NONCE_HEADER = "X-Webhook-Nonce";
    static final String SECRET_HEADER = "X-Webhook-Secret";

    private static final long NONCE_TTL_SECONDS = 600;

    private final AppSecurityProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebhookSignatureFilter(ObjectProvider<AppSecurityProperties> propertiesProvider,
                                  ObjectProvider<StringRedisTemplate> redisProvider) {
        // 用 ObjectProvider 延迟解析：在 @WebMvcTest 等局部上下文中依赖缺失时降级为 null，
        // 此时 doFilterInternal 会直接放行（properties == null 分支），不干扰 Controller 测试。
        this.properties = propertiesProvider.getIfAvailable();
        this.redis = redisProvider.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (properties == null || !properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        // 缓存 body 供签名校验，同时保证下游 @RequestBody 能正常反序列化。
        CachedBodyRequestWrapper wrapped = new CachedBodyRequestWrapper(request);
        if (authenticate(wrapped)) {
            filterChain.doFilter(wrapped, response);
        } else {
            writeUnauthorized(response);
        }
    }

    private boolean authenticate(CachedBodyRequestWrapper request) {
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (signature != null && !signature.isBlank()) {
            return authenticateHmac(request);
        }
        if (properties.isWebhookHmacRequired()) {
            return false;
        }
        return isValidSecret(request.getHeader(SECRET_HEADER), properties.getWebhookSecret());
    }

    private boolean authenticateHmac(CachedBodyRequestWrapper request) {
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String nonce = request.getHeader(NONCE_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (blank(timestamp) || blank(nonce) || blank(signature)) {
            return false;
        }
        long presentedAt;
        try {
            presentedAt = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        long tolerance = Math.max(0L, properties.getWebhookTimestampToleranceSeconds());
        if (Math.abs(now - presentedAt) > tolerance) {
            return false;
        }
        if (!claimNonce(nonce.trim())) {
            return false;
        }
        String rawBody = new String(request.getBody(), StandardCharsets.UTF_8);
        String content = timestamp.trim() + "." + nonce.trim() + "." + rawBody;
        String expected = hmacHex(content, properties.getWebhookSecret());
        return expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8));
    }

    private boolean claimNonce(String nonce) {
        if (redis == null) {
            return false;
        }
        try {
            Boolean claimed = redis.opsForValue()
                    .setIfAbsent("webhook:nonce:" + nonce, "1", Duration.ofSeconds(NONCE_TTL_SECONDS));
            return Boolean.TRUE.equals(claimed);
        } catch (Exception e) {
            // Redis 不可用或异常时 fail-closed，拒绝认领失败的 nonce。
            return false;
        }
    }

    private boolean isValidSecret(String presented, String expected) {
        if (expected == null || expected.isBlank() || presented == null || presented.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacHex(String content, String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Void> body = ApiResponse.error(401, "Unauthorized");
        body.setRequestId(UUID.randomUUID().toString());
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    /**
     * 在构造时读完整 body 并缓存，重写 getInputStream/getReader 返回缓存，
     * 使签名校验在调用 Controller 之前完成，且下游能正常反序列化。
     */
    private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        private byte[] getBody() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // 同步读取，无需异步监听。
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
