# ReproTrail Server

[![CI](https://github.com/sarimmehdi/reprotrail-server/actions/workflows/ci.yml/badge.svg)](https://github.com/sarimmehdi/reprotrail-server/actions/workflows/ci.yml)

Kotlin and Spring Boot service for authenticated ReproTrail ingestion, immutable trace storage, metadata indexing, hosted replay orchestration, downloads, retention, deletion, and audit records.

> [!IMPORTANT]
> This repository contains the Milestone 4 hosted-ingestion and Milestone 5 hosted-replay alpha APIs. Its public API and deployment model are not stable, and it must currently be used only with synthetic or controlled internal-test traces and APKs.

## Architecture boundary

ReproTrail Server consumes the tagged [`reprotrail-spec`](https://github.com/sarimmehdi/reprotrail-spec) contract. It never edits an accepted trace or regenerates capture history in place.

- Spring MVC owns the authenticated HTTP boundary.
- Domain services own authorization-independent ingestion, retention, and deletion rules.
- PostgreSQL owns project-scoped metadata, credential hashes, idempotency records, replay leases, diagnostic receipts, and audit records.
- S3-compatible object storage owns immutable trace, application, and replay-artifact bodies.
- Android upload belongs to [`reprotrail-android`](https://github.com/sarimmehdi/reprotrail-android), not this repository.
- Replay execution belongs to the isolated [`reprotrail-replay-worker`](https://github.com/sarimmehdi/reprotrail-replay-worker), which accesses inputs only through lease-bound HTTP endpoints.

## Alpha API status

| Method and path | Status | Authority | Outcome |
| --- | --- | --- | --- |
| `POST /v1/projects/{projectId}/traces` | Implemented | Project ingest token | Validate and idempotently store one immutable trace |
| `GET /v1/projects/{projectId}/traces` | Implemented | Project developer token | Search available tenant-scoped metadata with bounded keyset pagination |
| `GET /v1/projects/{projectId}/traces/{traceId}` | Implemented | Project developer token | Read available trace metadata |
| `GET /v1/projects/{projectId}/traces/{traceId}/content` | Implemented | Project developer token | Download and audit access to the immutable trace |
| `DELETE /v1/projects/{projectId}/traces/{traceId}` | Implemented | Project developer token | Delete metadata and content while retaining an audit tombstone |
| `POST /v1/projects/{projectId}/traces/{traceId}/replay-jobs` | Implemented | Project developer token | Create a bounded replay job for a registered APK |
| `GET /v1/projects/{projectId}/replay-jobs/{jobId}` | Implemented | Project developer token | Read project-scoped replay status |

The internal worker API leases jobs, heartbeats ownership, downloads the trace and registered APK, uploads immutable diagnostics, and completes or fails an attempt under `/internal/v1/projects/{projectId}/replay-jobs/**`. It accepts only a project-scoped `rt_worker_` credential. This API is not an Android-client or developer-client surface.

Retries use the trace session UUID as `Idempotency-Key`. Reusing a key with different bytes is rejected. Ingest credentials never grant read, replay, administration, or deletion access.

Bearer credentials use `rt_ingest_<credential-uuid>.<base64url-secret>`. Only an HMAC-SHA-256 digest is stored in PostgreSQL; the raw token and server-side pepper must never be persisted or logged by this service.

Developer credentials use the separate `rt_dev_<credential-uuid>.<base64url-secret>` prefix and `developer_credentials` table. They grant project-scoped read, download, and delete authority but never ingestion authority. Downloads and deletions retain the acting developer credential ID in append-oriented audit events. List cursors are opaque and clients must not construct or edit them.

Trace search accepts optional `query`, `packageName`, `captureMode`, `startedAfter`, and `startedBefore` parameters. `query` performs a case-insensitive substring match against package name and session ID; the other fields are exact or half-open time-range filters. Filters compose with the opaque cursor and never cross the authenticated project boundary.

Worker credentials use `rt_worker_<credential-uuid>.<base64url-secret>` and grant only project-scoped replay work. A lease expires after a bounded interval, can be recovered by another worker, and rejects stale heartbeats, downloads, uploads, completion, and failure reports. Concurrent PostgreSQL claims cannot return the same queued job to two workers.

Authentication and the configured request-size limit run in the Spring Security filter chain before Spring MVC reads the JSON body. PostgreSQL atomically reserves tenant-scoped idempotency, while an S3 conditional write preserves the first original artifact. Failed object writes leave retryable metadata rather than reporting a completed ingest.

Deletion uses explicit `deleting` and `delete_failed` states because PostgreSQL and S3 do not share a transaction. Retention reuses the same deletion path with a system actor. Reconciliation inspects stale ingestion rows and resumes interrupted deletion without overwriting an original artifact.

## Development

Requirements:

- JDK 21
- Docker-compatible runtime for PostgreSQL and object-storage integration tests
- PostgreSQL 17-compatible database for a running service
- Existing S3-compatible bucket with conditional `PutObject` support

Run the fast suite:

```shell
./gradlew check
```

No production secrets, traces, APKs, or replay artifacts belong in this public repository. Application artifacts and worker credentials are provisioned out of band in the alpha; there is intentionally no public arbitrary-APK upload endpoint.

The test suite automatically runs real PostgreSQL and MinIO contracts when Docker is available and reports them as skipped otherwise. These contracts cover Flyway, credentials, concurrent replay leases, immutable traces, immutable replay diagnostics, and authenticated ingestion. GitHub Actions has Docker and must remain the authoritative infrastructure gate.

See [`docs/operations.md`](docs/operations.md) for required configuration, credential boundaries, least-privilege storage access, failure recovery, and deployment checks. Project and credential provisioning remain deliberately out of band until the administrative API milestone; do not expose direct database access to Android clients.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
