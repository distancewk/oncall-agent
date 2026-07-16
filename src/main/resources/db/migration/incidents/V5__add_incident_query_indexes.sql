create index if not exists idx_incidents_status_severity_updated
    on incidents (status, severity, updated_at desc);

create index if not exists idx_diagnosis_runs_incident_review_status
    on diagnosis_runs (incident_id, human_review_status, status, created_at desc);
