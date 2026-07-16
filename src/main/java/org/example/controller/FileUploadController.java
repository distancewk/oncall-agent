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
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@RestController
public class FileUploadController {

    private static final int MAX_FILENAME_LENGTH = 180;
    private static final java.util.regex.Pattern SAFE_FILENAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,179}");

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

        if (originalFilename.contains("..")
                || originalFilename.contains("/")
                || originalFilename.contains("\\")) {
            throw new IllegalArgumentException("非法的路径格式或文件名包含不允许的字符");
        }
        Path filenamePath = Paths.get(originalFilename).getFileName();
        if (filenamePath == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String cleanFilename = filenamePath.toString();
        if (!isSafeFilename(cleanFilename)) {
            throw new IllegalArgumentException("文件名包含不允许的字符或格式");
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

        String documentId = org.example.service.DocumentIdentity.documentId(cleanFilename);
        Path documentDir = uploadDir.resolve(documentId).normalize().toAbsolutePath();
        if (!documentDir.startsWith(uploadDir)) {
            throw new IllegalArgumentException("不允许将文件上传到指定目录外");
        }
        Files.createDirectories(documentDir);

        Path temporaryFile = Files.createTempFile(uploadDir, ".upload-", ".tmp");
        try {
            try (java.io.InputStream input = file.getInputStream()) {
                Files.copy(input, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            }
            String contentHash = org.example.service.DocumentIdentity.contentHash(temporaryFile);
            String extension = fileExtension.toLowerCase(Locale.ROOT);
            Path filePath = documentDir.resolve(contentHash + (extension.isEmpty() ? "" : "." + extension))
                    .normalize().toAbsolutePath();
            if (!filePath.startsWith(documentDir)) {
                throw new IllegalArgumentException("不允许将文件上传到指定目录外");
            }
            if (!Files.exists(filePath)) {
                try {
                    Files.move(temporaryFile, filePath, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporaryFile, filePath);
                }
            }

            logger.info("文件上传成功, documentId: {}, size: {}", documentId, file.getSize());
            IndexTaskStatus indexTask = indexTaskStatusService.createTaskAndEnqueue(
                    cleanFilename, filePath.toString(), documentId, contentHash);

            FileUploadRes response = new FileUploadRes(
                    cleanFilename, filePath.toString(), file.getSize(),
                    indexTask.getTaskId(), "INDEXING", "文件已接收，索引处理中");
            response.setDocumentId(documentId);
            response.setContentHash(contentHash);
            return ResponseEntity.ok(ApiResponse.success(response));
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
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

    private boolean isSafeFilename(String filename) {
        return filename.length() <= MAX_FILENAME_LENGTH
                && !filename.equals(".")
                && !filename.equals("..")
                && !filename.contains("..")
                && !filename.contains("/")
                && !filename.contains("\\")
                && filename.chars().noneMatch(Character::isISOControl)
                && SAFE_FILENAME.matcher(filename).matches();
    }
}
