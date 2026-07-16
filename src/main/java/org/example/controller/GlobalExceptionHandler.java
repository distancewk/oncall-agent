package org.example.controller;

import org.example.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        String requestId = requestId();
        logger.warn("参数错误, requestId: {}, type: {}", requestId, ex.getClass().getSimpleName());
        return errorResponse(400, "请求参数无效", requestId);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxSizeException(MaxUploadSizeExceededException ex) {
        logger.warn("文件过大, type: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error(413, "上传的文件超出了最大限制大小"));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleIOException(IOException ex) {
        String requestId = requestId();
        logger.error("IO异常, requestId: {}", requestId, ex);
        return errorResponse(500, "文件读写失败，请稍后重试", requestId);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        String requestId = requestId();
        logger.error("系统异常, requestId: {}", requestId, ex);
        return errorResponse(500, "内部服务器错误，请稍后重试", requestId);
    }

    private ResponseEntity<ApiResponse<Void>> errorResponse(int code, String message, String requestId) {
        ApiResponse<Void> response = ApiResponse.error(code, message);
        response.setRequestId(requestId);
        return ResponseEntity.status(code).body(response);
    }

    private String requestId() {
        return UUID.randomUUID().toString();
    }
}
