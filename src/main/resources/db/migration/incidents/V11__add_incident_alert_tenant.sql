alter table incident_alerts add column if not exists tenant_id varchar(128) not null default 'default';

update incident_alerts alerts
set tenant_id = coalesce(
    (select incidents.tenant_id from incidents where incidents.id = alerts.incident_id),
    'default'
)
where tenant_id is null or trim(tenant_id) = '';

drop index if exists uk_incident_alerts_alert_id;

create unique index if not exists uk_incident_alerts_tenant_alert_id
    on incident_alerts (tenant_id, alert_id);

create index if not exists idx_incident_alerts_tenant_received
    on incident_alerts (tenant_id, received_at);
