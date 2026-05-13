package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.ApiResponse;
import org.example.dto.FileUploadRes;
import org.example.dto.IndexTaskStatus;
import org.example.service.IndexTaskStatusService;
import org.example.service.VectorIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class FileUploadControllerTest {

    @TempDir
    private Path uploadDir;

    @Mock
    private VectorIndexService vectorIndexService;

    private FileUploadController controller;
    private IndexTaskStatusService indexTaskStatusService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        FileUploadConfig config = new FileUploadConfig();
        config.setPath(uploadDir.toString());
        config.setAllowedExtensions("txt,md");

        controller = new FileUploadController();
        indexTaskStatusService = new IndexTaskStatusService();
        ReflectionTestUtils.setField(controller, "fileUploadConfig", config);
        ReflectionTestUtils.setField(controller, "vectorIndexService", vectorIndexService);
        ReflectionTestUtils.setField(controller, "indexTaskStatusService", indexTaskStatusService);
        ReflectionTestUtils.setField(controller, "executor", (Executor) Runnable::run);
    }

    @Test
    void upload_shouldReturnIndexingStatusForAcceptedMarkdown() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "runbook.md", "text/markdown", "# hello".getBytes());

        ApiResponse<FileUploadRes> body = controller.upload(file).getBody();

        assertNotNull(body);
        assertEquals(200, body.getCode());
    }

    @Test
    void upload_shouldPersistFileAndSubmitAsyncIndexing() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "runbook.md", "text/markdown", "# hello".getBytes());

        ApiResponse<FileUploadRes> response = controller.upload(file).getBody();

        assertNotNull(response);
        FileUploadRes data = response.getData();
        assertEquals("runbook.md", data.getFileName());
        assertNotNull(data.getIndexTaskId());
        assertEquals("INDEXING", data.getIndexStatus());
        assertEquals("文件已接收，索引处理中", data.getMessage());
        assertTrue(Files.exists(uploadDir.resolve("runbook.md")));
        verify(vectorIndexService).indexSingleFile(uploadDir.resolve("runbook.md").toAbsolutePath().toString());
    }

    @Test
    void getUploadStatus_shouldExposeCompletedAsyncIndexingState() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "runbook.md", "text/markdown", "# hello".getBytes());

        FileUploadRes data = controller.upload(file).getBody().getData();
        ApiResponse<IndexTaskStatus> statusResponse = controller.getUploadStatus(data.getIndexTaskId()).getBody();

        assertNotNull(statusResponse);
        assertEquals("COMPLETED", statusResponse.getData().getStatus());
        assertEquals("索引完成", statusResponse.getData().getMessage());
    }

    @Test
    void getUploadStatus_shouldExposeFailedAsyncIndexingState() throws Exception {
        doThrow(new RuntimeException("boom")).when(vectorIndexService).indexSingleFile(anyString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "runbook.md", "text/markdown", "# hello".getBytes());

        FileUploadRes data = controller.upload(file).getBody().getData();
        ApiResponse<IndexTaskStatus> statusResponse = controller.getUploadStatus(data.getIndexTaskId()).getBody();

        assertNotNull(statusResponse);
        assertEquals("FAILED", statusResponse.getData().getStatus());
        assertEquals("索引失败", statusResponse.getData().getMessage());
        assertTrue(statusResponse.getData().getErrorMessage().contains("boom"));
    }

    @Test
    void upload_shouldRejectUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "runbook.exe", "application/octet-stream", "bad".getBytes());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> controller.upload(file));

        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    @Test
    void upload_shouldRejectPathTraversalFilename() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "../runbook.md", "text/markdown", "# bad".getBytes());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> controller.upload(file));

        assertTrue(ex.getMessage().contains("非法的路径格式"));
    }
}
