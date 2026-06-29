package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.ApiResponse;
import org.example.dto.FileUploadRes;
import org.example.dto.IndexTaskStatus;
import org.example.service.IndexTaskStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private IndexTaskStatusService indexTaskStatusService;

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<FileUploadRes>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        // 安全清理路径，防止目录遍历漏洞
        String cleanFilename = StringUtils.cleanPath(originalFilename);
        if (cleanFilename.contains("..")) {
            throw new IllegalArgumentException("非法的路径格式: " + cleanFilename);
        }

        String fileExtension = getFileExtension(cleanFilename);
        if (!isAllowedExtension(fileExtension)) {
            throw new IllegalArgumentException("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        String uploadPath = fileUploadConfig.getPath();
        Path uploadDir = Paths.get(uploadPath).normalize().toAbsolutePath();
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }

        // 使用安全的绝对路径，并再次验证文件并未越出指定的上传目录
        Path filePath = uploadDir.resolve(cleanFilename).normalize().toAbsolutePath();
        if (!filePath.startsWith(uploadDir)) {
            throw new IllegalArgumentException("不允许将文件上传到指定目录外");
        }
        
        // 如果文件已存在，先删除旧文件（实现覆盖更新）
        if (Files.exists(filePath)) {
            logger.info("文件已存在，将覆盖: {}", filePath);
            Files.delete(filePath);
        }
        
        Files.copy(file.getInputStream(), filePath);

        logger.info("文件上传成功: {}", filePath);

        IndexTaskStatus indexTask = indexTaskStatusService.createTaskAndEnqueue(
                cleanFilename,
                filePath.toString()
        );

        FileUploadRes response = new FileUploadRes(
                cleanFilename,
                filePath.toString(),
                file.getSize(),
                indexTask.getTaskId(),
                "INDEXING",
                "文件已接收，索引处理中"
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/api/upload/status/{taskId}")
    public ResponseEntity<ApiResponse<IndexTaskStatus>> getUploadStatus(@PathVariable String taskId) {
        return indexTaskStatusService.getStatus(taskId)
                .map(status -> ResponseEntity.ok(ApiResponse.success(status)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, "索引任务不存在")));
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }
}
