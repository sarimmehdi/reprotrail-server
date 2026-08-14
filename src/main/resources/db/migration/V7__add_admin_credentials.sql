create table admin_credentials (
    id uuid primary key,
    project_id uuid not null references projects (id),
    token_digest bytea not null,
    created_at timestamptz not null default current_timestamp,
    expires_at timestamptz,
    revoked_at timestamptz,
    constraint admin_credentials_digest_length check (octet_length(token_digest) = 32),
    constraint admin_credentials_project_id_unique unique (project_id, id)
);

create index admin_credentials_project_id_idx on admin_credentials (project_id);
