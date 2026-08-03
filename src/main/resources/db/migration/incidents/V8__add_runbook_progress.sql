alter table diagnosis_runs add column if not exists runbook_id varchar(128);
alter table diagnosis_runs add column if not exists runbook_status varchar(32)
    not null default 'NOT_STARTED';
alter table diagnosis_runs add column if not exists runbook_step integer
    not null default 0;
alter table diagnosis_runs add column if not exists runbook_required_tools text
    not null default '[]';
alter table diagnosis_runs add column if not exists runbook_completed_tools text
    not null default '[]';

create index if not exists idx_diagnosis_runs_runbook_status
    on diagnosis_runs (runbook_id, runbook_status, created_at);
