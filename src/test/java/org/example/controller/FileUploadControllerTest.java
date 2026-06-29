package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppJobProperties;
import org.example.config.FileUploadConfig;
import org.example.dto.ApiResponse;
import org.example.dto.BackgroundJobRecord;
import org.example.dto.FileUploadRes;
import org.example.dto.IndexTaskStatus;
import org.example.service.BackgroundJobRepository;
import org.example.service.IncidentSchemaMigrator;
import org.example.service.IndexTaskStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
class FileUploadControllerTest {

    @TempDir
    private Path uploadDir;

    private FileUploadController controller;
    private IndexTaskStatusService indexTaskStatusService;

    @BeforeEach
    void setUp() {
        FileUploadConfig config = new FileUploadConfig();
        config.setPath(uploadDir.toString());
        config.setAllowedExtensions("txt,md");

        controller = new FileUploadController();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:" + uploadDir.resolve("index-db"));
        dataSource.setUsername("");
        dataSource.setPassword("");
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        indexTaskStatusService = new IndexTaskStatusService(
                dataSource, migrator, new BackgroundJobRepository(dataSource, migrator),
                new ObjectMapper(), new AppJobProperties());
        ReflectionTestUtils.setField(controller, "fileUploadConfig", config);
        ReflectionTestUtils.setField(controller, "indexTaskStatusService", indexTaskStatusService);
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
    void upload_shouldPersistFileAndEnqueueIndexingWithoutDirectVectorCall() throws Exception {
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
        IndexTaskStatus storedTask = indexTaskStatusService.getStatus(data.getIndexTaskId()).orElseThrow();
        assertEquals("INDEXING", storedTask.getStatus());
        assertEquals("runbook.md", storedTask.getFileName());
    }

    @Test
    void getUploadStatus_shouldExposeQueuedIndexingState() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "runbook.md", "text/markdown", "# hello".getBytes());

        FileUploadRes data = controller.upload(file).getBody().getData();
        ApiResponse<IndexTaskStatus> statusResponse = controller.getUploadStatus(data.getIndexTaskId()).getBody();

        assertNotNull(statusResponse);
        assertEquals("INDEXING", statusResponse.getData().getStatus());
        assertEquals("文件已接收，索引处理中", statusResponse.getData().getMessage());
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

    @Test
    void createTaskAndEnqueue_shouldRollbackTaskWhenJobInsertFails() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:" + uploadDir.resolve("failing-jobs-db"));
        dataSource.setUsername("");
        dataSource.setPassword("");
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        IndexTaskStatusService service = new IndexTaskStatusService(
                dataSource,
                migrator,
                new FailingBackgroundJobRepository(dataSource, migrator),
                new ObjectMapper(),
                new AppJobProperties()
        );

        assertThrows(IllegalStateException.class,
                () -> service.createTaskAndEnqueue("runbook.md", "/tmp/runbook.md"));
        assertTrue(service.listStatuses().isEmpty());
    }

    @Test
    void createTaskAndEnqueue_shouldPreserveJobFailureWhenRollbackAlsoFails() {
        DriverManagerDataSource delegate = dataSource("rollback-failure-db");
        FailingTransactionDataSource dataSource = new FailingTransactionDataSource(delegate);
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(delegate);
        IndexTaskStatusService service = new IndexTaskStatusService(
                dataSource,
                migrator,
                new FailingBackgroundJobRepository(delegate, migrator),
                new ObjectMapper(),
                new AppJobProperties()
        );
        dataSource.failRollback();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createTaskAndEnqueue("runbook.md", "/tmp/runbook.md"));

        assertEquals("simulated job insert failure", failure.getCause().getMessage());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals("simulated rollback failure",
                failure.getCause().getSuppressed()[0].getMessage());
    }

    @Test
    void createTaskAndEnqueue_shouldPreserveConnectionOpenFailure() {
        DriverManagerDataSource delegate = dataSource("connection-open-failure-db");
        FailingTransactionDataSource dataSource = new FailingTransactionDataSource(delegate);
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(delegate);
        IndexTaskStatusService service = new IndexTaskStatusService(
                dataSource,
                migrator,
                new BackgroundJobRepository(delegate, migrator),
                new ObjectMapper(),
                new AppJobProperties()
        );
        dataSource.failConnectionOpen();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createTaskAndEnqueue("runbook.md", "/tmp/runbook.md"));

        assertEquals("simulated connection open failure", failure.getCause().getMessage());
    }

    @Test
    void createTaskAndEnqueue_shouldPreserveAutoCommitFailure() {
        DriverManagerDataSource delegate = dataSource("auto-commit-failure-db");
        FailingTransactionDataSource dataSource = new FailingTransactionDataSource(delegate);
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(delegate);
        IndexTaskStatusService service = new IndexTaskStatusService(
                dataSource,
                migrator,
                new BackgroundJobRepository(delegate, migrator),
                new ObjectMapper(),
                new AppJobProperties()
        );
        dataSource.failAutoCommit();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createTaskAndEnqueue("runbook.md", "/tmp/runbook.md"));

        assertEquals("simulated auto-commit failure", failure.getCause().getMessage());
    }

    private DriverManagerDataSource dataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:" + uploadDir.resolve(name));
        dataSource.setUsername("");
        dataSource.setPassword("");
        return dataSource;
    }

    private static final class FailingBackgroundJobRepository extends BackgroundJobRepository {

        private FailingBackgroundJobRepository(DriverManagerDataSource dataSource,
                                               IncidentSchemaMigrator migrator) {
            super(dataSource, migrator);
        }

        @Override
        public BackgroundJobRecord enqueue(
                Connection connection,
                String jobType,
                String businessKey,
                String payload,
                int maxAttempts,
                long now) throws SQLException {
            throw new SQLException("simulated job insert failure");
        }
    }

    private static final class FailingTransactionDataSource extends AbstractDataSource {

        private final DataSource delegate;
        private boolean failConnectionOpen;
        private boolean failAutoCommit;
        private boolean failRollback;

        private FailingTransactionDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        private void failRollback() {
            failRollback = true;
        }

        private void failConnectionOpen() {
            failConnectionOpen = true;
        }

        private void failAutoCommit() {
            failAutoCommit = true;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (failConnectionOpen) {
                throw new SQLException("simulated connection open failure");
            }
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            if (failConnectionOpen) {
                throw new SQLException("simulated connection open failure");
            }
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (failAutoCommit
                                && method.getName().equals("setAutoCommit")
                                && Boolean.FALSE.equals(args[0])) {
                            throw new SQLException("simulated auto-commit failure");
                        }
                        if (failRollback && method.getName().equals("rollback")) {
                            throw new SQLException("simulated rollback failure");
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
            );
        }
    }
}
