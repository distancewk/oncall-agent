package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;

/**
 * 接口鉴权拦截器
 * 检查 HTTP 请求头是否带有合法的 API Key
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AuthInterceptor.class);

    @Value("${app.api.key}")
    private String apiKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authHeader = request.getHeader("Authorization");

        // 验证请求头是否存在并格式正确 (Bearer <Token>)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.warn("鉴权失败: 未提供 Authorization 头或格式不正确, URI: {}", request.getRequestURI());
            sendUnauthorizedResponse(response, "非法访问: 请在请求头中提供 Authorization: Bearer <API_KEY>");
            return false;
        }

        // 提取并对比 Token
        String token = authHeader.substring(7);
        if (!apiKey.equals(token)) {
            logger.warn("鉴权失败: 提供的 API Key 不正确, URI: {}", request.getRequestURI());
            sendUnauthorizedResponse(response, "非法访问: 提供的 API Key 无效");
            return false;
        }

        // 鉴权通过
        return true;
    }

    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws Exception {
        response.setStatus(401); // 401 Unauthorized
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter writer = response.getWriter();
        
        ApiResponse<String> apiResponse = ApiResponse.error(401, message);
        ObjectMapper mapper = new ObjectMapper();
        writer.write(mapper.writeValueAsString(apiResponse));
        writer.flush();
    }
}
