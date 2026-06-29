create table if not exists legacy_import_markers (
    marker_key varchar(512) primary key,
    imported_at bigint not null
);
