package dev.reprotrail.server.replay

import dev.reprotrail.server.security.DeveloperIdentity
import java.security.Principal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
internal class DeveloperReplayController(
    private val create: CreateReplayJob,
    private val reader: ReplayJobReader,
) {
    @PostMapping("/v1/projects/{projectId}/traces/{traceId}/replay-jobs")
    fun create(
        @PathVariable projectId: UUID,
        @PathVariable traceId: UUID,
        @RequestBody body: CreateReplayJobBody,
        principal: Principal,
    ): ResponseEntity<Any> {
        val identity = (principal as? Authentication)?.principal as? DeveloperIdentity
            ?: return error(HttpStatus.UNAUTHORIZED, "unauthorized", "Valid developer credentials are required.")
        if (body.repetitions !in 1..10 || body.attemptTimeoutSeconds !in 1..1800) {
            return error(HttpStatus.BAD_REQUEST, "invalid_replay_request", "Replay bounds are invalid.")
        }
        val request =
            CreateReplayJobRequest(
                projectId,
                traceId,
                body.applicationArtifactId,
                body.repetitions,
                Duration.ofSeconds(body.attemptTimeoutSeconds.toLong()),
                identity.credentialId,
            )
        return when (val result = create(request)) {
            is ReplayJobCreationResult.Created ->
                ResponseEntity.status(HttpStatus.CREATED).body(result.job.toResponse())
            ReplayJobCreationResult.TraceNotFound ->
                error(HttpStatus.NOT_FOUND, "trace_not_found", "Trace was not found.")
            ReplayJobCreationResult.ApplicationArtifactNotFound ->
                error(HttpStatus.NOT_FOUND, "application_artifact_not_found", "Application artifact was not found.")
            ReplayJobCreationResult.PackageMismatch ->
                error(HttpStatus.CONFLICT, "package_mismatch", "Trace and application artifact packages differ.")
        }
    }

    @GetMapping("/v1/projects/{projectId}/replay-jobs/{jobId}")
    fun status(
        @PathVariable projectId: UUID,
        @PathVariable jobId: UUID,
    ): ResponseEntity<Any> =
        reader.findJob(projectId, jobId)?.let { ResponseEntity.ok(it.toResponse()) }
            ?: error(HttpStatus.NOT_FOUND, "replay_job_not_found", "Replay job was not found.")

    private fun error(status: HttpStatus, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(ReplayErrorResponse(code, message))
}

internal data class CreateReplayJobBody(
    val applicationArtifactId: UUID,
    val repetitions: Int = 1,
    val attemptTimeoutSeconds: Int = 600,
)

internal data class ReplayJobResponse(
    val id: UUID,
    val traceId: UUID,
    val applicationArtifactId: UUID,
    val packageName: String,
    val repetitions: Int,
    val attemptTimeoutSeconds: Long,
    val state: String,
    val createdAt: Instant,
)

internal data class ReplayErrorResponse(val code: String, val message: String)

private fun ReplayJob.toResponse() =
    ReplayJobResponse(
        id,
        traceId,
        applicationArtifactId,
        packageName,
        repetitions,
        attemptTimeout.seconds,
        state.name.lowercase(),
        createdAt,
    )
