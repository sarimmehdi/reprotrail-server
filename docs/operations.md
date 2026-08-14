# ReproTrail Server operations

This document covers the hosted-ingestion and hosted-replay alpha. It is not a public multi-tenant SaaS deployment guide yet. Use synthetic or consented internal-test traces and APKs, and place the service behind TLS and a trusted ingress.

## Required configuration

Spring Boot relaxed binding maps the following environment variables to application properties.

| Environment variable | Required | Purpose |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | Yes | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Yes | Runtime or migration database principal |
| `SPRING_DATASOURCE_PASSWORD` | Yes | Database credential supplied by a secret manager |
| `REPROTRAIL_SECURITY_TOKEN_PEPPER_BASE64` | Yes | Base64-encoded random pepper containing at least 32 bytes |
| `REPROTRAIL_STORAGE_S3_BUCKET` | Yes | Existing private trace bucket |
| `REPROTRAIL_STORAGE_S3_REGION` | No | Signing region; defaults to `us-east-1` |
| `REPROTRAIL_STORAGE_S3_ENDPOINT` | S3-compatible only | MinIO or other S3-compatible endpoint |
| `REPROTRAIL_STORAGE_S3_ACCESS_KEY` | Together with secret key | Static credential for S3-compatible development; omit on AWS to use the default credential chain |
| `REPROTRAIL_STORAGE_S3_SECRET_KEY` | Together with access key | Static credential supplied by a secret manager |
| `REPROTRAIL_STORAGE_S3_PATH_STYLE` | S3-compatible only | Set `true` when the provider requires path-style bucket addressing |
| `REPROTRAIL_INGEST_MAX_TRACE_BYTES` | No | Maximum measured request body; defaults to 1,048,576 bytes |
| `REPROTRAIL_REPLAY_LEASE_DURATION` | No | Worker lease duration; defaults to `PT2M`, minimum 10 seconds, maximum 5 minutes |
| `REPROTRAIL_REPLAY_MAX_ARTIFACT_BYTES` | No | Maximum diagnostic upload; defaults to 5,242,880 bytes, maximum 104,857,600 bytes |
| `REPROTRAIL_MAINTENANCE_ENABLED` | No | Enables reconciliation and retention on this instance; defaults to `false` |
| `REPROTRAIL_MAINTENANCE_FIXED_DELAY` | No | ISO-8601 delay between runs; defaults to `PT15M` |
| `REPROTRAIL_MAINTENANCE_RETENTION` | No | ISO-8601 trace retention duration; defaults to `P30D` |
| `REPROTRAIL_MAINTENANCE_RETENTION_BATCH_SIZE` | No | Maximum expired traces per run; defaults to 100, maximum 1,000 |
| `REPROTRAIL_MAINTENANCE_RECONCILIATION_STALE_AFTER` | No | Age before an incomplete transition is inspected; defaults to `PT15M` |
| `REPROTRAIL_MAINTENANCE_RECONCILIATION_BATCH_SIZE` | No | Maximum stale transitions per run; defaults to 100, maximum 1,000 |

Never place production values in `application.yml`, shell history, Gradle properties, GitHub workflow YAML, container images, or this repository. Rotate the token pepper only through a planned credential migration: changing it immediately invalidates every existing ingest token.

## Database boundary

Flyway owns schema history under `db/migration`. PostgreSQL stores projects, HMAC credential digests, trace metadata, idempotency reservations, storage state, application-artifact metadata, replay jobs, leases, diagnostic receipts, and audit-event structure. Raw trace JSON, APKs, and diagnostic bodies belong only in object storage.

Production deployments should use separate migration and runtime database roles. Spring Boot supports dedicated Flyway connection settings; the runtime role should not be able to alter schema. Backups must preserve PostgreSQL metadata and the S3 bucket as one recovery set.

Project, application-artifact, and credential creation are currently administrative, out-of-band operations. A future provisioning command or API must generate at least 32 random secret bytes, return the full token once, and persist only its HMAC digest in `ingest_credentials`, `developer_credentials`, or `worker_credentials`. Register an APK only after uploading it under a server-derived private object key and recording its SHA-256 digest, byte length, and package name. Until that workflow exists, the hosted alpha is not self-service.

## Object-storage boundary

The bucket must already exist and remain private. The runtime identity needs `s3:PutObject`, `s3:GetObject`, and `s3:DeleteObject` on the configured object prefix. It does not need bucket listing, ACL, or public-read permissions. If deployment separates ingestion from access and maintenance processes, assign only the subset each process actually uses.

ReproTrail sends `If-None-Match: *`, which Amazon S3 uses to atomically prevent overwrites. Enforce conditional writes in the bucket policy where the provider supports it. S3-compatible providers must pass the MinIO contract test; older MinIO releases silently ignored this precondition and are unsafe for immutable ingestion.

Each trace and replay-diagnostic object records a ReproTrail SHA-256 metadata value. A retry after a precondition failure is accepted only when both that digest and the content length match. A mismatch is an idempotency conflict and never overwrites the original.

## Hosted replay boundary

A developer can create a replay only when the trace and registered application artifact belong to the same project and declare the same package. Requests are bounded to 1–10 repetitions and a 1–1,800 second attempt timeout.

A worker authenticates with a separate `rt_worker_` token. PostgreSQL serializes claims per project, increments the attempt count atomically, and permits recovery only after lease expiry. Every heartbeat, input download, artifact upload, completion, and failure report is checked against the current project, worker credential, lease ID, job ID where applicable, state, and expiry. Do not expose internal worker routes through an untrusted public ingress unless that ingress applies the same TLS, body-limit, authentication-failure, and rate-limit controls as public APIs.

The worker may download only the trace and APK referenced by its active lease. Diagnostic names are server-keyed below `projects/{projectId}/replays/{jobId}/`, accept a restricted filename alphabet, are conditionally immutable, and must be uploaded before completion. The server verifies every completion receipt against object-store metadata. A stale worker cannot replace diagnostics or complete a recovered job.

Run workers on dedicated, resource-limited hosts with dedicated wipeable AVDs. Do not colocate replay execution with the API service or grant workers PostgreSQL/S3 credentials. Treat APKs, traces, Maestro flows, screenshots, videos, XML, and logs as untrusted and potentially sensitive.

## Failure and retry behavior

Trace metadata moves through three storage states:

1. `pending` — PostgreSQL won the idempotency reservation; content is not confirmed yet.
2. `available` — the immutable object exists and ingestion can be reported complete.
3. `failed` — the object write failed or conflicted; an identical retry may resume it.

Deletion adds two transition states:

4. `deleting` — PostgreSQL reserved deletion and the object or final metadata transaction may still be pending.
5. `delete_failed` — object deletion failed and can be retried by the API or reconciliation.

The database unique constraint serializes concurrent requests for the same project and idempotency key. The first complete request receives `201`; an identical retry receives `200`; changed bytes receive `409`. Storage or database outages remain server errors and clients should retry the same session ID with bounded exponential backoff.

Do not manually change a trace from `failed` to `available`. Confirm the object digest first or retry through the API so the normal invariant checks run.

Maintenance is disabled by default. Enable it on exactly one service replica unless deployment provides an external singleton scheduler; multiple maintenance executors add needless contention even though state transitions and object deletion are idempotent. Reconciliation runs before retention and emits only aggregate counts. Alert whenever either failure count is non-zero or stale transition counts keep growing.

## Security and observability

- Terminate TLS at the service or a trusted ingress and reject plaintext traffic before it reaches the application.
- Expose only `/actuator/health` and `/actuator/info` publicly. All unmatched application routes are denied.
- Never enable HTTP request-body logging, bearer-token logging, SQL parameter logging, or object-content logging.
- Alert on sustained authentication failures, `413` responses, storage failures, and growing `pending` or `failed` counts without attaching raw request data.
- Treat package names, session and replay identifiers, timing metadata, worker IDs, and object keys as potentially sensitive telemetry.
- Restrict developer-token provisioning because those credentials can download and delete every trace in their project.
- Keep audit retention longer than trace retention and protect the audit table from application-level update or delete paths.

## Verification and deployment gate

Run `./gradlew check bootJar` on JDK 21. With Docker available, this starts real PostgreSQL and MinIO containers and verifies Flyway, credential lookup, concurrent idempotency, S3 immutability, and the complete authenticated HTTP path. A build that skips infrastructure tests is suitable for local feedback only, not release approval.

Before deployment, confirm all of the following:

- CI is green with PostgreSQL and MinIO infrastructure tests executed rather than skipped.
- The schema migration has been reviewed and backed up.
- The bucket is private, pre-created, encrypted, and denies non-conditional writes where supported.
- Database, S3, and pepper secrets come from the deployment platform's secret manager.
- Ingress body limits are at least as strict as the application limit.
- Exactly one replica has maintenance enabled, and retention has been approved for the deployment's consent and legal policy.
- Health checks do not expose credentials, tenant data, or trace content.
- Replay workers have no database or object-store credentials and use dedicated wipeable AVDs.
- Lease duration, artifact size, attempt timeout, worker concurrency, CPU, memory, disk, and wall-clock limits are explicitly approved.
- Rollback preserves both new database rows and already-written immutable trace, APK, and diagnostic objects.
