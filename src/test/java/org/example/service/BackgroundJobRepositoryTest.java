package org.example.service;

import org.example.dto.BackgroundJobRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundJobRepositoryTest {

    @TempDir
    private Path tempDir;

    private DriverManagerDataSource dataSource;
    private BackgroundJobRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:" + tempDir.resolve("jobs-db"));
        dataSource.setUsername("");
        dataSource.setPassword("");
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        migrator.migrate();
        repository = new BackgroundJobRepository(dataSource);
    }

    @Test
    void enqueueWithConnection_shouldParticipateInCallerTransaction() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            repository.enqueue(connection, "DIAGNOSIS", "run-tx", "{}", 2, 100L);
            connection.rollback();
        }

        assertTrue(repository.findByBusinessKey("DIAGNOSIS", "run-tx").isEmpty());
    }

    @Test
    void enqueue_shouldDeduplicateByTypeAndBusinessKey() {
        BackgroundJobRecord first = repository.enqueue(
                "DIAGNOSIS", "run-1", "{\"incidentId\":\"inc-1\"}", 2, 100L);
        BackgroundJobRecord repeated = repository.enqueue(
                "DIAGNOSIS", "run-1", "{\"incidentId\":\"inc-1\"}", 2, 200L);

        assertEquals(first.getJobId(), repeated.getJobId());
        assertEquals("QUEUED", repeated.getStatus());
    }

    @Test
    void requestCancelWithConnection_shouldRollbackWithCallerTransaction() throws Exception {
        BackgroundJobRecord job = repository.enqueue(
                "DIAGNOSIS", "run-cancel-tx", "{}", 2, 100L);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            repository.requestCancel(connection, "DIAGNOSIS", "run-cancel-tx", 200L);
            connection.rollback();
        }

        assertFalse(repository.findById(job.getJobId()).orElseThrow().isCancelRequested());
    }

    @Test
    void claim_shouldReturnEachJobToOnlyOneWorker() throws Exception {
        repository.enqueue("DIAGNOSIS", "run-1", "{}", 2, 100L);
        repository.enqueue("DIAGNOSIS", "run-2", "{}", 2, 100L);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<BackgroundJobRecord>> first = executor.submit(() -> {
                start.await();
                return repository.claimNext("worker-a", 200L, 1_000L);
            });
            Future<Optional<BackgroundJobRecord>> second = executor.submit(() -> {
                start.await();
                return repository.claimNext("worker-b", 200L, 1_000L);
            });
            start.countDown();

            BackgroundJobRecord firstClaim = first.get().orElseThrow();
            BackgroundJobRecord secondClaim = second.get().orElseThrow();
            assertNotEquals(firstClaim.getJobId(), secondClaim.getJobId());
            assertEquals("RUNNING", firstClaim.getStatus());
            assertEquals(1, firstClaim.getAttemptCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLease_shouldMoveJobToRetry() {
        BackgroundJobRecord job = repository.enqueue("DIAGNOSIS", "run-1", "{}", 2, 100L);
        repository.claimNext("worker-a", 200L, 100L).orElseThrow();

        assertEquals(1, repository.recoverExpiredLeases(301L));
        BackgroundJobRecord recovered = repository.findById(job.getJobId()).orElseThrow();
        assertEquals("RETRY", recovered.getStatus());
        assertEquals(301L, recovered.getAvailableAt());
    }

    @Test
    void cancel_shouldPreventClaimAndRetry() {
        BackgroundJobRecord queued = repository.enqueue("DIAGNOSIS", "run-queued", "{}", 2, 100L);
        repository.requestCancel("DIAGNOSIS", "run-queued", 150L);
        assertTrue(repository.claimNext("worker-a", 200L, 1_000L).isEmpty());
        assertEquals("CANCELLED", repository.findById(queued.getJobId()).orElseThrow().getStatus());

        BackgroundJobRecord running = repository.enqueue("DIAGNOSIS", "run-running", "{}", 2, 300L);
        BackgroundJobRecord claimed = repository.claimNext("worker-a", 300L, 1_000L).orElseThrow();
        repository.requestCancel("DIAGNOSIS", "run-running", 350L);
        repository.retryOrFail(claimed.getJobId(), "worker-a", claimed.getLeaseVersion(),
                "interrupted", 400L, 350L);

        BackgroundJobRecord cancelled = repository.findById(running.getJobId()).orElseThrow();
        assertEquals("CANCELLED", cancelled.getStatus());
        assertTrue(cancelled.isCancelRequested());
    }

    @Test
    void attemptsExhausted_shouldFailJob() {
        BackgroundJobRecord job = repository.enqueue("INDEX", "task-1", "{}", 1, 100L);
        BackgroundJobRecord claimed = repository.claimNext("worker-a", 100L, 1_000L).orElseThrow();

        repository.retryOrFail(claimed.getJobId(), "worker-a", claimed.getLeaseVersion(),
                "boom", 200L, 150L);

        BackgroundJobRecord failed = repository.findById(job.getJobId()).orElseThrow();
        assertEquals("FAILED", failed.getStatus());
        assertEquals("boom", failed.getLastError());
        assertFalse(failed.isCancelRequested());
    }

    @Test
    void staleLeaseVersion_shouldNotOverrideNewClaim() {
        BackgroundJobRecord job = repository.enqueue("DIAGNOSIS", "run-fence", "{}", 2, 100L);
        BackgroundJobRecord firstClaim = repository.claimNext("worker-a", 200L, 100L).orElseThrow();
        assertEquals(1L, firstClaim.getLeaseVersion());

        // 旧租约过期被回收，新 Worker 重新认领（lease_version 递增）。
        repository.recoverExpiredLeases(301L);
        BackgroundJobRecord secondClaim = repository.claimNext("worker-b", 302L, 1_000L).orElseThrow();
        assertEquals(2L, secondClaim.getLeaseVersion());

        // 旧 Worker 用过期 lease_version 尝试完成，不应覆盖新 Worker 的状态。
        repository.complete(firstClaim.getJobId(), "worker-a", firstClaim.getLeaseVersion(), 303L);

        BackgroundJobRecord persisted = repository.findById(job.getJobId()).orElseThrow();
        assertEquals("RUNNING", persisted.getStatus());
        assertEquals("worker-b", persisted.getLeaseOwner());
        assertEquals(2L, persisted.getLeaseVersion());
    }
}
