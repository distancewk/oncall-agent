alter table index_tasks add column if not exists document_id varchar(128);
alter table index_tasks add column if not exists content_hash varchar(128);

create index if not exists idx_index_tasks_document_id
    on index_tasks (document_id, updated_at);
