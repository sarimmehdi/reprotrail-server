# ReproTrail Server

[![CI](https://github.com/sarimmehdi/reprotrail-server/actions/workflows/ci.yml/badge.svg)](https://github.com/sarimmehdi/reprotrail-server/actions/workflows/ci.yml)

Kotlin and Spring Boot service for authenticated ReproTrail ingestion, immutable trace storage, metadata indexing, downloads, retention, deletion, and audit records.

> [!IMPORTANT]
> This repository is the Milestone 4 hosted-ingestion alpha. Its public API and deployment model are not stable, and it must currently be used only with synthetic or controlled internal-test traces.

## Architecture boundary

ReproTrail Server consumes the tagged [`reprotrail-spec`](https://github.com/sarimmehdi/reprotrail-spec) contract. It never edits an accepted trace or regenerates capture history in place.

- Spring MVC owns the authenticated HTTP boundary.
- Domain services own authorization-independent ingestion, retention, and deletion rules.
- PostgreSQL owns project-scoped metadata, credential hashes, idempotency records, and audit records.
- S3-compatible object storage owns the immutable original JSON artifact.
- Android upload belongs to [`reprotrail-android`](https://github.com/sarimmehdi/reprotrail-android), not this repository.
- Replay execution belongs to a future isolated `reprotrail-replay-worker` repository.

## Alpha API status

| Method and path | Status | Authority | Outcome |
| --- | --- | --- | --- |
| `POST /v1/projects/{projectId}/traces` | Implemented | Project ingest token | Validate and idempotently store one immutable trace |
| `GET /v1/projects/{projectId}/traces` | Planned | Project developer token | List tenant-scoped trace metadata |
| `GET /v1/projects/{projectId}/traces/{traceId}` | Planned | Project developer token | Read trace metadata |
| `GET /v1/projects/{projectId}/traces/{traceId}/content` | Planned | Project developer token | Download the immutable trace |
| `DELETE /v1/projects/{projectId}/traces/{traceId}` | Planned | Project developer token | Delete metadata and content while retaining an audit tombstone |

Retries use the trace session UUID as `Idempotency-Key`. Reusing a key with different bytes is rejected. Ingest credentials never grant read, replay, administration, or deletion access.

Bearer credentials use `rt_ingest_<credential-uuid>.<base64url-secret>`. Only an HMAC-SHA-256 digest is stored in PostgreSQL; the raw token and server-side pepper must never be persisted or logged by this service.

Authentication and the configured request-size limit run in the Spring Security filter chain before Spring MVC reads the JSON body. PostgreSQL atomically reserves tenant-scoped idempotency, while an S3 conditional write preserves the first original artifact. Failed object writes leave retryable metadata rather than reporting a completed ingest.

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

No production secrets or trace artifacts belong in this public repository.

The test suite automatically runs real PostgreSQL and MinIO contracts when Docker is available and reports them as skipped otherwise. GitHub Actions has Docker and must remain the authoritative infrastructure gate.

See [`docs/operations.md`](docs/operations.md) for required configuration, credential boundaries, least-privilege storage access, failure recovery, and deployment checks. Project and credential provisioning remain deliberately out of band until the administrative API milestone; do not expose direct database access to Android clients.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
