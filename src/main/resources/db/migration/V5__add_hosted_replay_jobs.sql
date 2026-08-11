create table application_artifacts (
    project_id uuid not null references projects (id),
    id uuid not null,
    package_name varchar(255) not null,
    object_key varchar(512) not null,
    content_sha256 bytea not null,
    size_bytes bigint not null,
    created_at timestamptz not null default current_timestamp,
    primary key (project_id, id),
    constraint application_artifacts_digest_length check (octet_length(content_sha256) = 32),
    constraint application_artifacts_size_non_negative check (size_bytes >= 0),
    constraint application_artifacts_object_key_unique unique (object_key)
);

create table worker_credentials (
    id uuid primary key,
    project_id uuid not null references projects (id),
    token_digest bytea not null,
    created_at timestamptz not null default current_timestamp,
    expires_at timestamptz,
    revoked_at timestamptz,
    constraint worker_credentials_digest_length check (octet_length(token_digest) = 32),
    constraint worker_credentials_project_id_unique unique (project_id, id)
);

create index worker_credentials_project_id_idx on worker_credentials (project_id);

create table replay_jobs (
    project_id uuid not null references projects (id),
    id uuid not null,
    trace_id uuid not null,
    application_artifact_id uuid not null,
    package_name varchar(255) not null,
    repetitions integer not null,
    attempt_timeout_seconds integer not null,
    state varchar(16) not null,
    attempt_count integer not null default 0,
    max_attempts integer not null default 3,
    lease_id uuid,
    lease_owner_credential_id uuid,
    lease_expires_at timestamptz,
    created_by_credential_id uuid not null,
    passed_repetitions integer,
    failed_repetitions integer,
    failure_code varchar(64),
    failure_summary varchar(240),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (project_id, id),
    foreign key (project_id, trace_id) references traces (project_id, session_id) on delete cascade,
    foreign key (project_id, application_artifact_id) references application_artifacts (project_id, id),
    foreign key (project_id, created_by_credential_id) references developer_credentials (project_id, id),
    foreign key (project_id, lease_owner_credential_id) references worker_credentials (project_id, id),
    constraint replay_jobs_repetitions_range check (repetitions between 1 and 10),
    constraint replay_jobs_timeout_range check (attempt_timeout_seconds between 1 and 1800),
    constraint replay_jobs_attempt_range check (attempt_count between 0 and max_attempts and max_attempts between 1 and 10),
    constraint replay_jobs_state_known check (state in ('queued', 'leased', 'succeeded', 'failed')),
    constraint replay_jobs_lease_consistent check (
        (state = 'leased' and lease_id is not null and lease_owner_credential_id is not null and lease_expires_at is not null)
        or (state <> 'leased')
    )
);

create index replay_jobs_queue_idx on replay_jobs (project_id, state, created_at, id);
create index replay_jobs_lease_expiry_idx on replay_jobs (state, lease_expires_at) where state = 'leased';

create table replay_artifacts (
    project_id uuid not null,
    replay_job_id uuid not null,
    name varchar(128) not null,
    kind varchar(32) not null,
    object_key varchar(512) not null,
    content_sha256 varchar(64) not null,
    size_bytes bigint not null,
    created_at timestamptz not null default current_timestamp,
    primary key (project_id, replay_job_id, name),
    foreign key (project_id, replay_job_id) references replay_jobs (project_id, id) on delete cascade,
    constraint replay_artifacts_kind_known check (kind in ('junit_report', 'maestro_output', 'device_log')),
    constraint replay_artifacts_digest_format check (content_sha256 ~ '^[a-f0-9]{64}$'),
    constraint replay_artifacts_size_non_negative check (size_bytes >= 0),
    constraint replay_artifacts_object_key_unique unique (object_key)
);

alter table audit_events add column replay_job_id uuid;

create index audit_events_replay_job_idx on audit_events (project_id, replay_job_id, occurred_at desc)
    where replay_job_id is not null;
