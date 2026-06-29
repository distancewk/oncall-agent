create table if not exists incidents (
    id varchar(128) primary key,
    aggregation_key varchar(512),
    title varchar(512),
    status varchar(64),
    severity varchar(64),
    alert_count integer,
    created_at bigint,
    updated_at bigint,
    last_alert_at bigint,
    payload text not null
);

create index if not exists idx_incidents_aggregation_key on incidents(aggregation_key);
create index if not exists idx_incidents_updated_at on incidents(updated_at);
create index if not exists idx_incidents_status on incidents(status);
create index if not exists idx_incidents_severity on incidents(severity);
