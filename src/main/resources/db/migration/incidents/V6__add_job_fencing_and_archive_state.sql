alter table background_jobs add column if not exists lease_token varchar(128);

alter table diagnosis_runs add column if not exists case_archive_status varchar(32)
    not null default 'NOT_REQUESTED';

update diagnosis_runs
set case_archive_status = 'COMPLETED'
where case_archived = true and case_archive_status = 'NOT_REQUESTED';

create index if not exists idx_background_jobs_terminal_updated
    on background_jobs (status, updated_at);

create index if not exists idx_index_tasks_terminal_updated
    on index_tasks (status, updated_at);

create index if not exists idx_chat_sessions_updated_id
    on chat_sessions (updated_at, session_id);
