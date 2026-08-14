package dev.reprotrail.server.persistence

import dev.reprotrail.server.replay.ApplicationArtifact
import dev.reprotrail.server.replay.ApplicationArtifactCatalog
import dev.reprotrail.server.replay.CompleteLeaseRequest
import dev.reprotrail.server.replay.CreateReplayJobRequest
import dev.reprotrail.server.replay.FailLeaseRequest
import dev.reprotrail.server.replay.LeaseHeartbeat
import dev.reprotrail.server.replay.LeaseRequest
import dev.reprotrail.server.replay.ReplayJob
import dev.reprotrail.server.replay.ReplayJobArtifact
import dev.reprotrail.server.replay.ReplayArtifactKind
import dev.reprotrail.server.replay.ReplayArtifactCatalog
import dev.reprotrail.server.replay.ReplayArtifactDownloadReference
import dev.reprotrail.server.replay.ReplayJobStore
import dev.reprotrail.server.replay.ReplayJobReader
import dev.reprotrail.server.replay.ReplayJobState
import dev.reprotrail.server.replay.ReplayLeaseStore
import dev.reprotrail.server.replay.WorkerReplayLease
import dev.reprotrail.server.replay.WorkerReplayFailureCode
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import dev.reprotrail.server.access.TraceArtifactReference
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

internal class JdbcReplayRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) : ApplicationArtifactCatalog, ReplayArtifactCatalog, ReplayJobStore, ReplayJobReader, ReplayLeaseStore {
    override fun findArtifact(projectId: UUID, artifactId: UUID): ApplicationArtifact? =
        jdbc.sql(
            """
            select id, package_name, object_key
            from application_artifacts
            where project_id = :projectId and id = :artifactId
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("artifactId", artifactId)
            .query { resultSet, _ ->
                ApplicationArtifact(
                    resultSet.getObject("id", UUID::class.java),
                    resultSet.getString("package_name"),
                    resultSet.getString("object_key"),
                )
            }.optional()
            .orElse(null)

    override fun create(request: CreateReplayJobRequest, job: ReplayJob): ReplayJob {
        val inserted =
            jdbc.sql(
                """
                insert into replay_jobs (
                    project_id, id, trace_id, application_artifact_id, package_name,
                    repetitions, attempt_timeout_seconds, state, created_by_credential_id,
                    created_at, updated_at
                ) values (
                    :projectId, :id, :traceId, :artifactId, :packageName,
                    :repetitions, :timeoutSeconds, 'queued', :actorId, :createdAt, :createdAt
                )
                """.trimIndent(),
            ).param("projectId", job.projectId)
                .param("id", job.id)
                .param("traceId", job.traceId)
                .param("artifactId", job.applicationArtifactId)
                .param("packageName", job.packageName)
                .param("repetitions", job.repetitions)
                .param("timeoutSeconds", job.attemptTimeout.seconds)
                .param("actorId", request.actorCredentialId)
                .param("createdAt", job.createdAt.atOffset(ZoneOffset.UTC))
                .update()
        check(inserted == 1) { "Replay job was not created." }
        return job
    }

    override fun findJob(projectId: UUID, jobId: UUID): ReplayJob? =
        jdbc.sql(
            """
            select id, project_id, trace_id, application_artifact_id, package_name,
                   repetitions, attempt_timeout_seconds, state, attempt_count, max_attempts,
                   passed_repetitions, failed_repetitions, failure_code, failure_summary,
                   created_at, updated_at
            from replay_jobs
            where project_id = :projectId and id = :jobId
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("jobId", jobId)
            .query(::mapJob)
            .optional()
            .orElse(null)
            ?.let { job -> job.copy(artifacts = findArtifacts(projectId, listOf(job.id))[job.id].orEmpty()) }

    override fun listJobs(projectId: UUID, traceId: UUID, limit: Int): List<ReplayJob> {
        require(limit in 1..50) { "Replay job list size must be between 1 and 50." }
        val jobs =
            jdbc.sql(
                """
                select id, project_id, trace_id, application_artifact_id, package_name,
                       repetitions, attempt_timeout_seconds, state, attempt_count, max_attempts,
                       passed_repetitions, failed_repetitions, failure_code, failure_summary,
                       created_at, updated_at
                from replay_jobs
                where project_id = :projectId and trace_id = :traceId
                order by created_at desc, id desc
                limit :limit
                """.trimIndent(),
            ).param("projectId", projectId)
                .param("traceId", traceId)
                .param("limit", limit)
                .query(::mapJob)
                .list()
        val artifacts = findArtifacts(projectId, jobs.map(ReplayJob::id))
        return jobs.map { job -> job.copy(artifacts = artifacts[job.id].orEmpty()) }
    }

    override fun findReplayArtifact(projectId: UUID, jobId: UUID, name: String): ReplayArtifactDownloadReference? =
        jdbc.sql(
            """
            select jobs.trace_id, artifacts.kind, artifacts.name, artifacts.content_sha256,
                   artifacts.size_bytes, artifacts.created_at, artifacts.object_key
            from replay_artifacts artifacts
            join replay_jobs jobs
              on jobs.project_id = artifacts.project_id and jobs.id = artifacts.replay_job_id
            where artifacts.project_id = :projectId
              and artifacts.replay_job_id = :jobId
              and artifacts.name = :name
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("jobId", jobId)
            .param("name", name)
            .query { resultSet, _ ->
                ReplayArtifactDownloadReference(
                    traceId = resultSet.getObject("trace_id", UUID::class.java),
                    artifact =
                        ReplayJobArtifact(
                            kind = ReplayArtifactKind.valueOf(resultSet.getString("kind").uppercase()),
                            name = resultSet.getString("name"),
                            sha256 = resultSet.getString("content_sha256"),
                            sizeBytes = resultSet.getLong("size_bytes"),
                            createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                        ),
                    reference = TraceArtifactReference(resultSet.getString("object_key")),
                )
            }.optional()
            .orElse(null)

    private fun findArtifacts(projectId: UUID, jobIds: List<UUID>): Map<UUID, List<ReplayJobArtifact>> {
        if (jobIds.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            select replay_job_id, kind, name, content_sha256, size_bytes, created_at
            from replay_artifacts
            where project_id = :projectId and replay_job_id in (:jobIds)
            order by replay_job_id, created_at, name
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("jobIds", jobIds)
            .query { resultSet, _ ->
                resultSet.getObject("replay_job_id", UUID::class.java) to
                    ReplayJobArtifact(
                        kind = ReplayArtifactKind.valueOf(resultSet.getString("kind").uppercase()),
                        name = resultSet.getString("name"),
                        sha256 = resultSet.getString("content_sha256"),
                        sizeBytes = resultSet.getLong("size_bytes"),
                        createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    )
            }.list()
            .groupBy({ it.first }, { it.second })
    }

    override fun lease(request: LeaseRequest): WorkerReplayLease? =
        transactions.execute {
            jdbc.sql("select pg_advisory_xact_lock(:lockKey), 1 as acquired")
                .param("lockKey", request.projectId.mostSignificantBits xor request.projectId.leastSignificantBits)
                .query { resultSet, _ -> resultSet.getInt("acquired") }
                .single()
            val candidateId =
                jdbc.sql(
                    """
                    select id
                    from replay_jobs
                    where project_id = :projectId
                      and attempt_count < max_attempts
                      and (state = 'queued' or (state = 'leased' and lease_expires_at <= :now))
                    order by created_at, id
                    limit 1
                    """.trimIndent(),
                ).param("projectId", request.projectId)
                    .param("now", request.now.atOffset(ZoneOffset.UTC))
                    .query(UUID::class.java)
                    .optional()
                    .orElse(null)
                    ?: return@execute null
            jdbc.sql(
                """
                update replay_jobs
                set state = 'leased', lease_id = :leaseId,
                    lease_owner_credential_id = :workerId, lease_expires_at = :expiresAt,
                    attempt_count = attempt_count + 1, updated_at = :now
                where project_id = :projectId and id = :candidateId
                returning lease_id, id, project_id, trace_id, application_artifact_id,
                          package_name, repetitions, attempt_timeout_seconds, lease_expires_at
                """.trimIndent(),
            ).param("projectId", request.projectId)
                .param("candidateId", candidateId)
                .param("workerId", request.workerCredentialId)
                .param("leaseId", request.leaseId)
                .param("now", request.now.atOffset(ZoneOffset.UTC))
                .param("expiresAt", request.expiresAt.atOffset(ZoneOffset.UTC))
                .query(::mapLease)
                .optional()
                .orElse(null)
        }

    override fun heartbeat(request: LeaseHeartbeat): Boolean =
        jdbc.sql(
            """
            update replay_jobs
            set lease_expires_at = :expiresAt, updated_at = :now
            where project_id = :projectId and lease_id = :leaseId
              and lease_owner_credential_id = :workerId and state = 'leased'
              and lease_expires_at > :now
            """.trimIndent(),
        ).param("projectId", request.projectId)
            .param("leaseId", request.leaseId)
            .param("workerId", request.workerCredentialId)
            .param("now", request.now.atOffset(ZoneOffset.UTC))
            .param("expiresAt", request.expiresAt.atOffset(ZoneOffset.UTC))
            .update() == 1

    override fun findActive(
        projectId: UUID,
        workerCredentialId: UUID,
        jobId: UUID,
        leaseId: UUID,
        now: java.time.Instant,
    ): WorkerReplayLease? =
        jdbc.sql(
            """
            select lease_id, id, project_id, trace_id, application_artifact_id,
                   package_name, repetitions, attempt_timeout_seconds, lease_expires_at
            from replay_jobs
            where project_id = :projectId and id = :jobId and lease_id = :leaseId
              and lease_owner_credential_id = :workerId and state = 'leased'
              and lease_expires_at > :now
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("jobId", jobId)
            .param("leaseId", leaseId)
            .param("workerId", workerCredentialId)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .query(::mapLease)
            .optional()
            .orElse(null)

    override fun complete(request: CompleteLeaseRequest): Boolean =
        transactions.execute {
            val updated =
                jdbc.sql(
                    """
                    update replay_jobs
                    set state = 'succeeded', passed_repetitions = :passed,
                        failed_repetitions = :failed, lease_id = null,
                        lease_owner_credential_id = null, lease_expires_at = null,
                        updated_at = :now
                    where project_id = :projectId and id = :jobId and lease_id = :leaseId
                      and lease_owner_credential_id = :workerId and state = 'leased'
                      and lease_expires_at > :now
                      and repetitions = :passed + :failed
                    """.trimIndent(),
                ).param("projectId", request.projectId)
                    .param("jobId", request.jobId)
                    .param("leaseId", request.leaseId)
                    .param("workerId", request.workerCredentialId)
                    .param("passed", request.completion.passedRepetitions)
                    .param("failed", request.completion.failedRepetitions)
                    .param("now", request.now.atOffset(ZoneOffset.UTC))
                    .update()
            if (updated != 1) return@execute false

            request.completion.artifacts.forEach { artifact ->
                jdbc.sql(
                    """
                    insert into replay_artifacts (
                        project_id, replay_job_id, name, kind, object_key,
                        content_sha256, size_bytes, created_at
                    ) values (
                        :projectId, :jobId, :name, :kind, :objectKey,
                        :sha256, :sizeBytes, :now
                    )
                    """.trimIndent(),
                ).param("projectId", request.projectId)
                    .param("jobId", request.jobId)
                    .param("name", artifact.name)
                    .param("kind", artifact.kind.name.lowercase())
                    .param("objectKey", "projects/${request.projectId}/replays/${request.jobId}/${artifact.name}")
                    .param("sha256", artifact.sha256)
                    .param("sizeBytes", artifact.sizeBytes)
                    .param("now", request.now.atOffset(ZoneOffset.UTC))
                    .update()
            }
            true
        }

    override fun fail(request: FailLeaseRequest): Boolean =
        jdbc.sql(
            """
            update replay_jobs
            set state = 'failed', failure_code = :failureCode,
                failure_summary = :failureSummary, lease_id = null,
                lease_owner_credential_id = null, lease_expires_at = null,
                updated_at = :now
            where project_id = :projectId and id = :jobId and lease_id = :leaseId
              and lease_owner_credential_id = :workerId and state = 'leased'
              and lease_expires_at > :now
            """.trimIndent(),
        ).param("projectId", request.projectId)
            .param("jobId", request.jobId)
            .param("leaseId", request.leaseId)
            .param("workerId", request.workerCredentialId)
            .param("failureCode", request.failure.code.name.lowercase())
            .param("failureSummary", request.failure.summary)
            .param("now", request.now.atOffset(ZoneOffset.UTC))
            .update() == 1
}

private fun mapJob(resultSet: ResultSet, rowNumber: Int): ReplayJob {
    check(rowNumber >= 0)
    return ReplayJob(
        id = resultSet.getObject("id", UUID::class.java),
        projectId = resultSet.getObject("project_id", UUID::class.java),
        traceId = resultSet.getObject("trace_id", UUID::class.java),
        applicationArtifactId = resultSet.getObject("application_artifact_id", UUID::class.java),
        packageName = resultSet.getString("package_name"),
        repetitions = resultSet.getInt("repetitions"),
        attemptTimeout = Duration.ofSeconds(resultSet.getLong("attempt_timeout_seconds")),
        state = ReplayJobState.valueOf(resultSet.getString("state").uppercase()),
        createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        attemptCount = resultSet.getInt("attempt_count"),
        maxAttempts = resultSet.getInt("max_attempts"),
        passedRepetitions = (resultSet.getObject("passed_repetitions") as? Number)?.toInt(),
        failedRepetitions = (resultSet.getObject("failed_repetitions") as? Number)?.toInt(),
        failureCode = resultSet.getString("failure_code")?.let { WorkerReplayFailureCode.valueOf(it.uppercase()) },
        failureSummary = resultSet.getString("failure_summary"),
        updatedAt = resultSet.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
    )
}

private fun mapLease(resultSet: ResultSet, rowNumber: Int): WorkerReplayLease {
    check(rowNumber >= 0)
    return WorkerReplayLease(
        leaseId = resultSet.getObject("lease_id", UUID::class.java),
        jobId = resultSet.getObject("id", UUID::class.java),
        projectId = resultSet.getObject("project_id", UUID::class.java),
        traceId = resultSet.getObject("trace_id", UUID::class.java),
        applicationArtifactId = resultSet.getObject("application_artifact_id", UUID::class.java),
        packageName = resultSet.getString("package_name"),
        repetitions = resultSet.getInt("repetitions"),
        attemptTimeout = Duration.ofSeconds(resultSet.getLong("attempt_timeout_seconds")),
        expiresAt = resultSet.getObject("lease_expires_at", OffsetDateTime::class.java).toInstant(),
    )
}
