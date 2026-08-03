alter table incidents add column if not exists tenant_id varchar(128) not null default 'default';

update incidents
set tenant_id = 'default'
where tenant_id is null or trim(tenant_id) = '';

drop index if exists uk_incidents_aggregation_key;

create unique index if not exists uk_incidents_tenant_aggregation_key
    on incidents (tenant_id, aggregation_key);

create index if not exists idx_incidents_tenant_updated
    on incidents (tenant_id, updated_at desc);
