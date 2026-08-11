package dev.reprotrail.server.replay

import dev.reprotrail.server.security.WorkerIdentity
import java.security.Principal
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/v1/projects/{projectId}/replay-jobs")
internal class WorkerReplayController(
    private val leaseNext: LeaseNextReplayJob,
    private val manage: ManageReplayLease,
    private val download: DownloadReplayInput,
) {
    @PostMapping("/lease")
    fun lease(
        @PathVariable projectId: UUID,
        principal: Principal,
    ): ResponseEntity<Any> {
        val identity = principal.workerIdentity() ?: return unauthorized()
        val lease = leaseNext(projectId, identity.credentialId) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(lease.toResponse())
    }

    @PostMapping("/leases/{leaseId}/heartbeat")
    fun heartbeat(
        @PathVariable projectId: UUID,
        @PathVariable leaseId: UUID,
        principal: Principal,
    ): ResponseEntity<Any> {
        val identity = principal.workerIdentity() ?: return unauthorized()
        return if (manage.heartbeat(projectId, identity.credentialId, leaseId)) {
            ResponseEntity.noContent().build()
        } else {
            conflict()
        }
    }

    @PostMapping("/{jobId}/leases/{leaseId}/complete")
    fun complete(
        @PathVariable projectId: UUID,
        @PathVariable jobId: UUID,
        @PathVariable leaseId: UUID,
        @RequestBody body: CompleteReplayBody,
        principal: Principal,
    ): ResponseEntity<Any> {
        val identity = principal.workerIdentity() ?: return unauthorized()
        val artifacts =
            runCatching {
                body.artifacts.map {
                    ReplayArtifactReceipt(ReplayArtifactKind.valueOf(it.kind.uppercase()), it.name, it.sha256, it.sizeBytes)
                }
            }.getOrElse { return invalidRequest() }
        val completion =
            runCatching { ReplayCompletion(body.passedRepetitions, body.failedRepetitions, artifacts) }
                .getOrElse { return invalidRequest() }
        return if (manage.complete(projectId, identity.credentialId, jobId, leaseId, completion)) {
            ResponseEntity.noContent().build()
        } else {
            conflict()
        }
    }

    @PostMapping("/{jobId}/leases/{leaseId}/fail")
    fun fail(
        @PathVariable projectId: UUID,
        @PathVariable jobId: UUID,
        @PathVariable leaseId: UUID,
        @RequestBody body: FailReplayBody,
        principal: Principal,
    ): ResponseEntity<Any> {
        val identity = principal.workerIdentity() ?: return unauthorized()
        val failure =
            runCatching { WorkerReplayFailure(WorkerReplayFailureCode.valueOf(body.code.uppercase()), body.summary) }
                .getOrElse { return invalidRequest() }
        return if (manage.fail(projectId, identity.credentialId, jobId, leaseId, failure)) {
            ResponseEntity.noContent().build()
        } else {
            conflict()
        }
    }

    @GetMapping("/{jobId}/leases/{leaseId}/trace", produces = ["application/vnd.reprotrail.trace+json"])
    fun trace(
        @PathVariable projectId: UUID,
        @PathVariable jobId: UUID,
        @PathVariable leaseId: UUID,
        principal: Principal,
    ): ResponseEntity<Any> = input(projectId, jobId, leaseId, principal, ReplayInputKind.TRACE)

    @GetMapping("/{jobId}/leases/{leaseId}/application", produces = ["application/vnd.android.package-archive"])
    fun application(
        @PathVariable projectId: UUID,
        @PathVariable jobId: UUID,
        @PathVariable leaseId: UUID,
        principal: Principal,
    ): ResponseEntity<Any> = input(projectId, jobId, leaseId, principal, ReplayInputKind.APPLICATION)

    private fun input(
        projectId: UUID,
        jobId: UUID,
        leaseId: UUID,
        principal: Principal,
        kind: ReplayInputKind,
    ): ResponseEntity<Any> {
        val identity = principal.workerIdentity() ?: return unauthorized()
        return when (val result = download(projectId, identity.credentialId, jobId, leaseId, kind)) {
            is ReplayInputDownloadResult.Found ->
                ResponseEntity.ok().contentType(
                    if (kind == ReplayInputKind.TRACE) {
                        MediaType.parseMediaType("application/vnd.reprotrail.trace+json")
                    } else {
                        MediaType.parseMediaType("application/vnd.android.package-archive")
                    },
                ).body(result.content)
            ReplayInputDownloadResult.NotFound -> conflict()
        }
    }

    private fun unauthorized(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ReplayErrorResponse("unauthorized", "Valid worker credentials are required."))

    private fun conflict(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ReplayErrorResponse("lease_not_active", "Replay lease is not active."))

    private fun invalidRequest(): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ReplayErrorResponse("invalid_replay_result", "Replay result is invalid."))
}

internal data class WorkerReplayLeaseResponse(
    val leaseId: UUID,
    val jobId: UUID,
    val projectId: UUID,
    val traceId: UUID,
    val applicationArtifactId: UUID,
    val packageName: String,
    val repetitions: Int,
    val attemptTimeoutSeconds: Long,
    val expiresAt: Instant,
)

internal data class ReplayArtifactBody(val kind: String, val name: String, val sha256: String, val sizeBytes: Long)
internal data class CompleteReplayBody(
    val passedRepetitions: Int,
    val failedRepetitions: Int,
    val artifacts: List<ReplayArtifactBody> = emptyList(),
)
internal data class FailReplayBody(val code: String, val summary: String)

private fun WorkerReplayLease.toResponse() =
    WorkerReplayLeaseResponse(
        leaseId,
        jobId,
        projectId,
        traceId,
        applicationArtifactId,
        packageName,
        repetitions,
        attemptTimeout.seconds,
        expiresAt,
    )

private fun Principal.workerIdentity(): WorkerIdentity? = (this as? Authentication)?.principal as? WorkerIdentity
