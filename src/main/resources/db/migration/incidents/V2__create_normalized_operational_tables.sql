alter table incidents add column if not exists version bigint not null default 0;

create unique index if not exists uk_incidents_aggregation_key
    on incidents (aggregation_key);

create table if not exists incident_alerts (
    id varchar(128) primary key,
    incident_id varchar(128),
    alert_id varchar(128),
    payload text not null,
    report text,
    received_at bigint not null,
    constraint fk_incident_alerts_incident
        foreign key (incident_id) references incidents(id) on delete cascade
);

create index if not exists idx_incident_alerts_incident_received
    on incident_alerts (incident_id, received_at);
create unique index if not exists uk_incident_alerts_alert_id
    on incident_alerts (alert_id);

create table if not exists diagnosis_runs (
    run_id varchar(128) primary key,
    incident_id varchar(128) not null,
    status varchar(32) not null,
    created_at bigint not null,
    started_at bigint,
    completed_at bigint,
    alert_context text,
    report text,
    error_message text,
    current_step text,
    progress_message text,
    current_tool varchar(256),
    reused_from_run_id varchar(128),
    reuse_reason text,
    reuse_confidence double precision not null default 0,
    reuse_validated_at bigint,
    quality_score integer not null default 0,
    quality_grade varchar(32),
    quality_summary text,
    quality_issues text not null default '[]',
    human_review_status varchar(32) not null default 'UNREVIEWED',
    human_review_comment text,
    human_reviewed_at bigint,
    case_archived boolean not null default false,
    case_document_id varchar(256),
    case_archive_message text,
    version bigint not null default 0,
    constraint fk_diagnosis_runs_incident
        foreign key (incident_id) references incidents(id) on delete cascade
);

create index if not exists idx_diagnosis_runs_incident_created
    on diagnosis_runs (incident_id, created_at);
create index if not exists idx_diagnosis_runs_status_created
    on diagnosis_runs (status, created_at);

create table if not exists diagnosis_evidence (
    id varchar(128) primary key,
    run_id varchar(128) not null,
    type varchar(64) not null,
    title text,
    content text,
    tool_name varchar(256),
    query_params text,
    time_range varchar(128),
    summary text,
    raw_fragment text,
    success boolean not null,
    error_message text,
    error_code varchar(128),
    attempt_count integer not null default 0,
    duration_ms bigint not null default 0,
    retryable boolean not null default false,
    created_at bigint not null,
    constraint fk_diagnosis_evidence_run
        foreign key (run_id) references diagnosis_runs(run_id) on delete cascade
);

create index if not exists idx_diagnosis_evidence_run_created
    on diagnosis_evidence (run_id, created_at);
create index if not exists idx_diagnosis_evidence_tool
    on diagnosis_evidence (run_id, tool_name, created_at);

create table if not exists chat_sessions (
    session_id varchar(256) primary key,
    title varchar(512) not null default '新对话',
    created_at bigint not null,
    updated_at bigint not null
);

create table if not exists chat_messages (
    id bigint generated always as identity primary key,
    session_id varchar(256) not null,
    role varchar(32) not null,
    content text not null,
    created_at bigint not null,
    constraint fk_chat_messages_session
        foreign key (session_id) references chat_sessions(session_id) on delete cascade
);

create index if not exists idx_chat_messages_session_id
    on chat_messages (session_id, id);
create index if not exists idx_chat_sessions_updated
    on chat_sessions (updated_at);

create table if not exists index_tasks (
    task_id varchar(128) primary key,
    file_name varchar(512) not null,
    file_path text not null,
    status varchar(32) not null,
    message text,
    error_message text,
    created_at bigint not null,
    updated_at bigint not null
);

create index if not exists idx_index_tasks_updated
    on index_tasks (updated_at);

create table if not exists background_jobs (
    job_id varchar(128) primary key,
    job_type varchar(64) not null,
    business_key varchar(256) not null,
    payload text not null,
    status varchar(32) not null,
    attempt_count integer not null default 0,
    max_attempts integer not null default 3,
    available_at bigint not null,
    lease_owner varchar(256),
    lease_expires_at bigint,
    heartbeat_at bigint,
    cancel_requested boolean not null default false,
    last_error text,
    created_at bigint not null,
    updated_at bigint not null
);

create unique index if not exists uk_background_jobs_business
    on background_jobs (job_type, business_key);
create index if not exists idx_background_jobs_claim
    on background_jobs (status, available_at, created_at);
create index if not exists idx_background_jobs_lease
    on background_jobs (status, lease_expires_at);
