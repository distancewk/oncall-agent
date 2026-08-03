create index if not exists idx_background_jobs_ready_queue
    on background_jobs (status, cancel_requested, available_at, created_at, job_id);
