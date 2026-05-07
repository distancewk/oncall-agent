package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 接口限流拦截器
 * 针对不同 IP 和不同接口进行限流防刷保护
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);

    // 针对每个 IP 的全局请求限流 (每秒 5 个请求)
    private final ConcurrentHashMap<String, RateLimiter> ipRateLimiters = new ConcurrentHashMap<>();
    
    // 针对重负载大模型接口的限流 (每秒总体只允许 2 个请求)
    private final RateLimiter heavyApiLimiter = RateLimiter.create(2.0);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = getClientIp(request);
        String requestURI = request.getRequestURI();

        // 1. IP 级别防刷限流
        RateLimiter ipLimiter = ipRateLimiters.computeIfAbsent(clientIp, k -> RateLimiter.create(5.0));
        if (!ipLimiter.tryAcquire(500, TimeUnit.MILLISECONDS)) {
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

    private void sendRateLimitResponse(HttpServletResponse response, String message) throws Exception {
        response.setStatus(429); // 429 Too Many Requests
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter writer = response.getWriter();
        
        ApiResponse<String> apiResponse = ApiResponse.error(429, message);
        ObjectMapper mapper = new ObjectMapper();
        writer.write(mapper.writeValueAsString(apiResponse));
        writer.flush();
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
