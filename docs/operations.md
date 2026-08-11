# ReproTrail Server operations

This document covers the hosted-ingestion alpha. It is not a public multi-tenant SaaS deployment guide yet. Use synthetic or consented internal-test traces and place the service behind TLS and a trusted ingress.

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

Never place production values in `application.yml`, shell history, Gradle properties, GitHub workflow YAML, container images, or this repository. Rotate the token pepper only through a planned credential migration: changing it immediately invalidates every existing ingest token.

## Database boundary

Flyway owns schema history under `db/migration`. PostgreSQL stores projects, HMAC credential digests, trace metadata, idempotency reservations, storage state, and audit-event structure. Raw trace JSON belongs only in object storage.

Production deployments should use separate migration and runtime database roles. Spring Boot supports dedicated Flyway connection settings; the runtime role should not be able to alter schema. Backups must preserve PostgreSQL metadata and the S3 bucket as one recovery set.

Project and credential creation are currently administrative, out-of-band operations. A future provisioning command or API must generate at least 32 random secret bytes, return the full token once, and persist only its HMAC digest. Until that exists, the hosted alpha is not self-service.

## Object-storage boundary

The bucket must already exist and remain private. On AWS, the ingestion identity needs `s3:PutObject` plus `s3:GetObject` (used by `HeadObject`) on the configured object prefix. It does not need list, delete, ACL, or public-read permissions for the current endpoint.

ReproTrail sends `If-None-Match: *`, which Amazon S3 uses to atomically prevent overwrites. Enforce conditional writes in the bucket policy where the provider supports it. S3-compatible providers must pass the MinIO contract test; older MinIO releases silently ignored this precondition and are unsafe for immutable ingestion.

Each object records a ReproTrail SHA-256 metadata value. A retry after a precondition failure is accepted only when both that digest and the content length match. A mismatch is an idempotency conflict and never overwrites the original.

## Failure and retry behavior

Trace metadata moves through three storage states:

1. `pending` — PostgreSQL won the idempotency reservation; content is not confirmed yet.
2. `available` — the immutable object exists and ingestion can be reported complete.
3. `failed` — the object write failed or conflicted; an identical retry may resume it.

The database unique constraint serializes concurrent requests for the same project and idempotency key. The first complete request receives `201`; an identical retry receives `200`; changed bytes receive `409`. Storage or database outages remain server errors and clients should retry the same session ID with bounded exponential backoff.

Do not manually change a trace from `failed` to `available`. Confirm the object digest first or retry through the API so the normal invariant checks run.

## Security and observability

- Terminate TLS at the service or a trusted ingress and reject plaintext traffic before it reaches the application.
- Expose only `/actuator/health` and `/actuator/info` publicly. All unmatched application routes are denied.
- Never enable HTTP request-body logging, bearer-token logging, SQL parameter logging, or object-content logging.
- Alert on sustained authentication failures, `413` responses, storage failures, and growing `pending` or `failed` counts without attaching raw request data.
- Treat package names, session identifiers, timing metadata, and object keys as potentially sensitive telemetry.
- Apply retention, deletion, and audit policies before accepting real user traces; those endpoints are not implemented in this alpha slice.

## Verification and deployment gate

Run `./gradlew check bootJar` on JDK 21. With Docker available, this starts real PostgreSQL and MinIO containers and verifies Flyway, credential lookup, concurrent idempotency, S3 immutability, and the complete authenticated HTTP path. A build that skips infrastructure tests is suitable for local feedback only, not release approval.

Before deployment, confirm all of the following:

- CI is green with infrastructure tests executed.
- The schema migration has been reviewed and backed up.
- The bucket is private, pre-created, encrypted, and denies non-conditional writes where supported.
- Database, S3, and pepper secrets come from the deployment platform's secret manager.
- Ingress body limits are at least as strict as the application limit.
- Health checks do not expose credentials, tenant data, or trace content.
- Rollback preserves both new database rows and already-written immutable objects.
