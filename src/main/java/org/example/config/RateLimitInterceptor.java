package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.example.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.annotation.PostConstruct;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 接口限流拦截器
 * 针对不同 IP 和不同接口进行限流防刷保护
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final int MAX_TRACKED_KEYS = 10_000;
    private static final String OVERFLOW_KEY = "__overflow__";
    private static final int REQUESTS_PER_SECOND = 5;
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('incr', KEYS[1]); "
                    + "if count == 1 then redis.call('expire', KEYS[1], ARGV[1]); end; "
                    + "return count", Long.class);

    @Value("${app.network.trusted-proxies:}")
    private String trustedProxies;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    // 为每个 IP 存储限流器及其最后访问时间
    private static class RateLimiterEntry {
        final RateLimiter limiter;
        volatile long lastAccessNanos;

        RateLimiterEntry(double permitsPerSecond) {
            this.limiter = RateLimiter.create(permitsPerSecond);
            this.lastAccessNanos = System.nanoTime();
        }

        void touch() {
            this.lastAccessNanos = System.nanoTime();
        }
    }

    // 针对每个 IP 的全局请求限流 (每秒 5 个请求)
    private final ConcurrentHashMap<String, RateLimiterEntry> ipRateLimiters = new ConcurrentHashMap<>();
    
    // 针对重负载大模型接口的限流 (每秒总体只允许 2 个请求)
    private final RateLimiter heavyApiLimiter = RateLimiter.create(2.0);

    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rate-limiter-cleanup");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        cleanupScheduler.scheduleAtFixedRate(() -> {
            int beforeSize = ipRateLimiters.size();
            long now = System.nanoTime();
            long staleThreshold = TimeUnit.MINUTES.toNanos(1);
            ipRateLimiters.forEach((ip, entry) -> {
                if (now - entry.lastAccessNanos > staleThreshold) {
                    ipRateLimiters.remove(ip, entry);
                }
            });
            int afterSize = ipRateLimiters.size();
            if (beforeSize != afterSize) {
                logger.info("IP限流器清理完成, 清理前: {}, 清理后: {}", beforeSize, afterSize);
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = getClientIp(request);
        String requestURI = request.getRequestURI();
        String limiterKey = buildLimiterKey(request, clientIp, requestURI);

        // 1. IP 级别防刷限流
        if (!ipRateLimiters.containsKey(limiterKey) && ipRateLimiters.size() >= MAX_TRACKED_KEYS) {
            limiterKey = OVERFLOW_KEY;
        }
        RateLimiterEntry entry = ipRateLimiters.computeIfAbsent(limiterKey,
                ignored -> new RateLimiterEntry(5.0));
        entry.touch();
        if (!tryAcquire(limiterKey, entry)) {
            logger.warn("限流拦截: IP {} 请求过快", clientIp);
            sendRateLimitResponse(response, "您的请求过于频繁，请稍后再试");
            return false;
        }

        // 2. 核心大模型接口全局限流
        if (requestURI.startsWith("/api/chat_stream") || requestURI.startsWith("/api/ai_ops")) {
            if (!heavyApiLimiter.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
                logger.warn("全局限流拦截: 重负载接口并发过高, URI: {}", requestURI);
                sendRateLimitResponse(response, "当前系统对话人数较多，请稍候重试");
                return false;
            }
        }

        return true;
    }

    private boolean tryAcquire(String limiterKey, RateLimiterEntry localEntry) {
        if (redisTemplate != null) {
            try {
                Long count = redisTemplate.execute(
                        RATE_LIMIT_SCRIPT,
                        java.util.List.of("superbiz:rate-limit:" + limiterKey),
                        "1");
                return count != null && count <= REQUESTS_PER_SECOND;
            } catch (RuntimeException e) {
                logger.warn("Redis 限流不可用，降级到本地限流, type: {}", e.getClass().getSimpleName());
            }
        }
        return localEntry.limiter.tryAcquire(500, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("PMD.CloseResource")
    private void sendRateLimitResponse(HttpServletResponse response, String message) throws Exception {
        response.setStatus(429); // 429 Too Many Requests
        response.setContentType("application/json;charset=UTF-8");
        // The servlet container owns this writer; closing it here can interfere with response handling.
        PrintWriter writer = response.getWriter();
        
        ApiResponse<String> apiResponse = ApiResponse.error(429, message);
        ObjectMapper mapper = new ObjectMapper();
        writer.write(mapper.writeValueAsString(apiResponse));
        writer.flush();
    }

    private String getClientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddress)) {
            return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    private boolean isTrustedProxy(String remoteAddress) {
        if (remoteAddress == null || trustedProxies == null || trustedProxies.isBlank()) {
            return false;
        }
        return java.util.Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .anyMatch(remoteAddress::equals);
    }

    private String buildLimiterKey(HttpServletRequest request, String clientIp, String requestUri) {
        String subject = request.getHeader("X-API-Key");
        if (subject == null || subject.isBlank()) {
            jakarta.servlet.http.Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (jakarta.servlet.http.Cookie cookie : cookies) {
                    if ("SB_SESSION".equals(cookie.getName())) {
                        subject = cookie.getValue();
                        break;
                    }
                }
            }
        }
        String subjectHash = subject == null || subject.isBlank()
                ? "anonymous"
                : Integer.toHexString(subject.hashCode());
        return clientIp + "|" + requestUri + "|" + subjectHash;
    }
}
