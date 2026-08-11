create table projects (
    id uuid primary key,
    name varchar(120) not null,
    created_at timestamptz not null default current_timestamp
);

create table ingest_credentials (
    id uuid primary key,
    project_id uuid not null references projects (id),
    token_digest bytea not null,
    created_at timestamptz not null default current_timestamp,
    expires_at timestamptz,
    revoked_at timestamptz,
    constraint ingest_credentials_digest_length check (octet_length(token_digest) = 32),
    constraint ingest_credentials_project_id_unique unique (project_id, id)
);

create index ingest_credentials_project_id_idx on ingest_credentials (project_id);

create table traces (
    project_id uuid not null references projects (id),
    session_id uuid not null,
    idempotency_key uuid not null,
    content_sha256 bytea not null,
    object_key varchar(512) not null,
    schema_version varchar(64) not null,
    started_at timestamptz not null,
    ended_at timestamptz,
    package_name varchar(255) not null,
    capture_mode varchar(32) not null,
    action_count integer not null,
    storage_state varchar(16) not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    primary key (project_id, session_id),
    constraint traces_project_idempotency_unique unique (project_id, idempotency_key),
    constraint traces_content_digest_length check (octet_length(content_sha256) = 32),
    constraint traces_action_count_positive check (action_count > 0),
    constraint traces_storage_state_known check (storage_state in ('pending', 'available', 'failed'))
);

create index traces_project_created_at_idx on traces (project_id, created_at desc);

create table audit_events (
    id uuid primary key,
    project_id uuid not null references projects (id),
    trace_id uuid,
    actor_credential_id uuid,
    action varchar(64) not null,
    occurred_at timestamptz not null default current_timestamp,
    details jsonb not null default '{}'::jsonb
);

create index audit_events_project_occurred_at_idx on audit_events (project_id, occurred_at desc);
