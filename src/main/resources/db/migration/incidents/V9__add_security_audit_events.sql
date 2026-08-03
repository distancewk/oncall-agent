create table if not exists security_audit_events (
    audit_id varchar(64) primary key,
    event_type varchar(64) not null,
    actor varchar(128) not null,
    role varchar(32),
    token_kind varchar(32),
    token_id varchar(128),
    http_method varchar(16) not null,
    request_path varchar(512) not null,
    outcome varchar(32) not null,
    status_code integer not null,
    request_id varchar(128),
    trace_id varchar(128),
    detail varchar(256),
    created_at bigint not null
);

create index if not exists idx_security_audit_created
    on security_audit_events (created_at desc, audit_id desc);

create index if not exists idx_security_audit_actor_created
    on security_audit_events (actor, created_at desc);

create index if not exists idx_security_audit_event_created
    on security_audit_events (event_type, created_at desc);
