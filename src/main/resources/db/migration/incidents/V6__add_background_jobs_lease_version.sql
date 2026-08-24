-- Fencing token：每次 claim 原子递增 lease_version，Worker 持 job_id + lease_owner + lease_version 三元组，
-- heartbeat/complete/retry/fail 等写操作必须同时匹配三元组，防止租约过期被回收后旧 Worker 覆盖新 Worker 结果。
alter table background_jobs add column if not exists lease_version bigint not null default 0;
