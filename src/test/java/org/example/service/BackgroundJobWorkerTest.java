package org.example.service;

import org.example.config.AppJobProperties;
import org.example.dto.BackgroundJobRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundJobWorkerTest {

    @TempDir
    private Path tempDir;

    private BackgroundJobRepository repository;
    private BackgroundJobWorker worker;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:" + tempDir.resolve("worker-db"));
        dataSource.setUsername("");
        dataSource.setPassword("");
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        migrator.migrate();
        repository = new BackgroundJobRepository(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.shutdown();
        }
    }

    @Test
    void pollOnce_shouldDispatchAndCompleteClaimedJob() throws Exception {
        CountDownLatch handled = new CountDownLatch(1);
        BackgroundJobHandler handler = new BackgroundJobHandler() {
            @Override
            public String jobType() {
                return "DIAGNOSIS";
            }

            @Override
            public void handle(BackgroundJobRecord job) {
                handled.countDown();
            }
        };
        worker = new BackgroundJobWorker(
                repository, List.of(handler), properties(), new RunningJobRegistry());
        BackgroundJobRecord job = repository.enqueue("DIAGNOSIS", "run-1", "{}", 2, 100L);

        worker.pollOnce(200L);

        assertTrue(handled.await(2, TimeUnit.SECONDS));
        awaitStatus(job.getJobId(), "COMPLETED");
    }

    @Test
    void handlerFailure_shouldRetryThenFailAtMaxAttempts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch handled = new CountDownLatch(2);
        BackgroundJobHandler handler = new BackgroundJobHandler() {
            @Override
            public String jobType() {
                return "INDEX";
            }

            @Override
            public void handle(BackgroundJobRecord job) {
                attempts.incrementAndGet();
                handled.countDown();
                throw new IllegalStateException("index failed");
            }
        };
        worker = new BackgroundJobWorker(
                repository, List.of(handler), properties(), new RunningJobRegistry());
        BackgroundJobRecord job = repository.enqueue("INDEX", "task-1", "{}", 2, 100L);

        worker.pollOnce(200L);
        awaitStatus(job.getJobId(), "RETRY");
        worker.pollOnce(System.currentTimeMillis() + 60_000L);

        assertTrue(handled.await(2, TimeUnit.SECONDS));
        awaitStatus(job.getJobId(), "FAILED");
        assertEquals(2, attempts.get());
    }

    private AppJobProperties properties() {
        AppJobProperties properties = new AppJobProperties();
        properties.setWorkerConcurrency(2);
        properties.setLeaseDurationMillis(1_000L);
        properties.setHeartbeatIntervalMillis(50L);
        properties.setRecoveryDelayMillis(10L);
        return properties;
    }

    private void awaitStatus(String jobId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(repository.findById(jobId).orElseThrow().getStatus())) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, repository.findById(jobId).orElseThrow().getStatus());
    }
}
