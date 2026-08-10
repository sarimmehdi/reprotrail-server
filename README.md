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

## Planned alpha API

| Method and path | Authority | Outcome |
| --- | --- | --- |
| `POST /v1/projects/{projectId}/traces` | Project ingest token | Validate and idempotently store one immutable trace |
| `GET /v1/projects/{projectId}/traces` | Project developer token | List tenant-scoped trace metadata |
| `GET /v1/projects/{projectId}/traces/{traceId}` | Project developer token | Read trace metadata |
| `GET /v1/projects/{projectId}/traces/{traceId}/content` | Project developer token | Download the immutable trace |
| `DELETE /v1/projects/{projectId}/traces/{traceId}` | Project developer token | Delete metadata and content while retaining an audit tombstone |

Retries use the trace session UUID as `Idempotency-Key`. Reusing a key with different bytes is rejected. Ingest credentials never grant read, replay, administration, or deletion access.

## Development

Requirements:

- JDK 21
- Docker-compatible runtime for PostgreSQL and object-storage integration tests

Run the fast suite:

```shell
./gradlew check
```

Infrastructure-backed integration tasks will be added with their adapters. No production secrets or trace artifacts belong in this public repository.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
