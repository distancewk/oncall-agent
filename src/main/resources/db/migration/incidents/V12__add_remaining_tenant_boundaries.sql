alter table diagnosis_runs add column if not exists tenant_id varchar(128) not null default 'default';
update diagnosis_runs
set tenant_id = coalesce((select incidents.tenant_id
                          from incidents
                          where incidents.id = diagnosis_runs.incident_id), 'default');

alter table diagnosis_evidence add column if not exists tenant_id varchar(128) not null default 'default';
update diagnosis_evidence
set tenant_id = coalesce((select diagnosis_runs.tenant_id
                          from diagnosis_runs
                          where diagnosis_runs.run_id = diagnosis_evidence.run_id), 'default');

alter table chat_sessions add column if not exists tenant_id varchar(128) not null default 'default';
alter table chat_messages add column if not exists tenant_id varchar(128) not null default 'default';
update chat_sessions
set tenant_id = 'default'
where tenant_id is null or trim(tenant_id) = '';
update chat_messages
set tenant_id = coalesce((select chat_sessions.tenant_id
                          from chat_sessions
                          where chat_sessions.session_id = chat_messages.session_id), 'default');

alter table index_tasks add column if not exists tenant_id varchar(128) not null default 'default';
alter table background_jobs add column if not exists tenant_id varchar(128) not null default 'default';
alter table security_audit_events add column if not exists tenant_id varchar(128) not null default 'default';

update index_tasks set tenant_id = 'default'
where tenant_id is null or trim(tenant_id) = '';
update background_jobs set tenant_id = 'default'
where tenant_id is null or trim(tenant_id) = '';
update security_audit_events set tenant_id = 'default'
where tenant_id is null or trim(tenant_id) = '';

drop index if exists uk_background_jobs_business;
create unique index if not exists uk_background_jobs_tenant_business
    on background_jobs (tenant_id, job_type, business_key);

create index if not exists idx_diagnosis_runs_tenant_incident_created
    on diagnosis_runs (tenant_id, incident_id, created_at);
create index if not exists idx_diagnosis_evidence_tenant_run_created
    on diagnosis_evidence (tenant_id, run_id, created_at);
create index if not exists idx_chat_sessions_tenant_updated
    on chat_sessions (tenant_id, updated_at, session_id);
create index if not exists idx_chat_messages_tenant_session
    on chat_messages (tenant_id, session_id, id);
create index if not exists idx_index_tasks_tenant_updated
    on index_tasks (tenant_id, updated_at, task_id);
create index if not exists idx_background_jobs_tenant_claim
    on background_jobs (tenant_id, status, available_at, created_at);
create index if not exists idx_security_audit_tenant_created
    on security_audit_events (tenant_id, created_at desc, audit_id desc);
