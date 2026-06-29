package org.example.service;

import org.example.dto.BackgroundJobRecord;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

@Component
public class RunningJobRegistry {

    private final Map<String, Future<?>> futuresByJobId = new ConcurrentHashMap<>();
    private final Map<String, String> jobIdsByBusinessKey = new ConcurrentHashMap<>();

    public void register(BackgroundJobRecord job, Future<?> future) {
        futuresByJobId.put(job.getJobId(), future);
        jobIdsByBusinessKey.put(key(job.getJobType(), job.getBusinessKey()), job.getJobId());
    }

    public void unregister(BackgroundJobRecord job) {
        futuresByJobId.remove(job.getJobId());
        jobIdsByBusinessKey.remove(key(job.getJobType(), job.getBusinessKey()), job.getJobId());
    }

    public boolean cancelByBusinessKey(String jobType, String businessKey) {
        String jobId = jobIdsByBusinessKey.get(key(jobType, businessKey));
        Future<?> future = jobId == null ? null : futuresByJobId.get(jobId);
        return future != null && future.cancel(true);
    }

    public void cancelAll() {
        futuresByJobId.values().forEach(future -> future.cancel(true));
        futuresByJobId.clear();
        jobIdsByBusinessKey.clear();
    }

    private String key(String jobType, String businessKey) {
        return jobType + "\u0000" + businessKey;
    }
}
