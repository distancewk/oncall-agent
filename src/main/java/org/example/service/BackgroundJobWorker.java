package org.example.service;

import jakarta.annotation.PreDestroy;
import org.example.config.AppJobProperties;
import org.example.dto.BackgroundJobRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class BackgroundJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundJobWorker.class);

    private final BackgroundJobRepository repository;
    private final Map<String, BackgroundJobHandler> handlers = new ConcurrentHashMap<>();
    private final AppJobProperties properties;
    private final RunningJobRegistry runningJobRegistry;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile boolean acceptingJobs = true;

    public BackgroundJobWorker(BackgroundJobRepository repository,
                               List<BackgroundJobHandler> handlers,
                               AppJobProperties properties,
                               RunningJobRegistry runningJobRegistry) {
        this.repository = repository;
        handlers.forEach(handler -> this.handlers.put(handler.jobType(), handler));
        this.properties = properties;
        this.runningJobRegistry = runningJobRegistry;
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getWorkerConcurrency()));
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    @Scheduled(fixedDelayString = "${app.jobs.poll-delay-millis:1000}")
    public void poll() {
        try {
            pollOnce(System.currentTimeMillis());
        } catch (RuntimeException e) {
            // Scheduled tasks must not surface repository failures as an uninformative UI stall.
            LOGGER.error("后台任务轮询失败，下一轮将自动重试", e);
        }
    }

    public void pollOnce(long now) {
        if (!acceptingJobs || !properties.isEnabled()) {
            return;
        }
        repository.claimNext(workerId, now, properties.getLeaseDurationMillis())
                .ifPresent(this::submit);
    }

    @Scheduled(fixedDelayString = "${app.jobs.recovery-delay-millis:15000}")
    public void recoverExpiredLeases() {
        if (acceptingJobs && properties.isEnabled()) {
            repository.recoverExpiredLeases(System.currentTimeMillis());
        }
    }

    private void submit(BackgroundJobRecord job) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            execute(job);
            return null;
        });
        runningJobRegistry.register(job, task);
        executor.execute(task);
    }

    private void execute(BackgroundJobRecord job) {
        long heartbeatInterval = Math.max(1L, properties.getHeartbeatIntervalMillis());
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        Thread[] executionThread = new Thread[1];
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!heartbeat(job)) {
                // 心跳返回租约丢失：fencing token 已被回收/重新认领，立即停止执行，
                // 避免旧 Worker 继续产生副作用并尝试覆盖新 Worker 的结果。
                leaseLost.set(true);
                Thread running = executionThread[0];
                if (running != null) {
                    running.interrupt();
                }
            }
        }, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
        try {
            executionThread[0] = Thread.currentThread();
            if (repository.isCancelRequested(job.getJobId())) {
                repository.retryOrFail(job.getJobId(), workerId, job.getLeaseVersion(), "任务已取消",
                        System.currentTimeMillis(), System.currentTimeMillis());
                return;
            }
            BackgroundJobHandler handler = handlers.get(job.getJobType());
            if (handler == null) {
                throw new IllegalStateException("未注册后台任务处理器: " + job.getJobType());
            }
            handler.handle(job);
            if (leaseLost.get()) {
                LOGGER.warn("后台任务租约已丢失，放弃提交结果, jobId: {}", job.getJobId());
                return;
            }
            repository.complete(job.getJobId(), workerId, job.getLeaseVersion(), System.currentTimeMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!leaseLost.get()) {
                repository.retryOrFail(job.getJobId(), workerId, job.getLeaseVersion(), "任务执行被中断",
                        retryAt(), System.currentTimeMillis());
            }
        } catch (Exception e) {
            LOGGER.warn("后台任务执行失败, jobId: {}, type: {}", job.getJobId(), job.getJobType(), e);
            if (!leaseLost.get()) {
                repository.retryOrFail(job.getJobId(), workerId, job.getLeaseVersion(), e.getMessage(),
                        retryAt(), System.currentTimeMillis());
            }
        } finally {
            heartbeat.cancel(false);
            runningJobRegistry.unregister(job);
        }
    }

    private boolean heartbeat(BackgroundJobRecord job) {
        try {
            return repository.heartbeat(job.getJobId(), workerId, job.getLeaseVersion(),
                    System.currentTimeMillis(), properties.getLeaseDurationMillis());
        } catch (RuntimeException e) {
            LOGGER.warn("后台任务心跳失败, jobId: {}", job.getJobId(), e);
            return false;
        }
    }

    private long retryAt() {
        return System.currentTimeMillis() + Math.max(1L, properties.getRecoveryDelayMillis());
    }

    @PreDestroy
    public void shutdown() {
        acceptingJobs = false;
        runningJobRegistry.cancelAll();
        executor.shutdownNow();
        heartbeatExecutor.shutdownNow();
    }
}
