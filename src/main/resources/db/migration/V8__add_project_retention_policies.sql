create table project_retention_policies (
    project_id uuid primary key references projects (id) on delete cascade,
    retain_for_days integer not null,
    updated_by_admin_credential_id uuid not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    foreign key (project_id, updated_by_admin_credential_id)
        references admin_credentials (project_id, id),
    constraint project_retention_days_range check (retain_for_days between 1 and 3650)
);
