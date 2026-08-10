package dev.reprotrail.server.ingest

import java.net.URI
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/projects/{projectId}/traces")
internal class TraceIngestController(
    private val ingestor: TraceIngestor,
) {
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE, TRACE_MEDIA_TYPE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun ingest(
        @PathVariable projectId: UUID,
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody content: ByteArray,
    ): ResponseEntity<Any> {
        val token = authorization.bearerToken()
            ?: return error(HttpStatus.UNAUTHORIZED, "unauthorized", "Valid ingest credentials are required.")
        val parsedIdempotencyKey = idempotencyKey.toUuidOrNull()
            ?: return error(HttpStatus.BAD_REQUEST, "invalid_idempotency_key", "Idempotency-Key must be a UUID.")

        return when (
            val outcome =
                ingestor.ingest(IngestRequest(projectId, token, parsedIdempotencyKey, content))
        ) {
            is IngestOutcome.Created ->
                ResponseEntity
                    .created(URI.create("/v1/projects/$projectId/traces/${outcome.trace.sessionId}"))
                    .body(outcome.trace)
            is IngestOutcome.AlreadyExists -> ResponseEntity.ok(outcome.trace)
            is IngestOutcome.InvalidTrace ->
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    ValidationErrorResponse(
                        code = "invalid_trace",
                        message = "Trace validation failed.",
                        issues = outcome.issues.map { issue ->
                            ValidationIssueResponse(
                                path = issue.path,
                                phase = issue.phase.name.lowercase(),
                                rule = issue.rule,
                                message = issue.message,
                            )
                        },
                    ),
                )
            IngestOutcome.IdempotencyMismatch ->
                error(
                    HttpStatus.BAD_REQUEST,
                    "idempotency_session_mismatch",
                    "Idempotency-Key must equal the trace session ID.",
                )
            IngestOutcome.Conflict ->
                error(
                    HttpStatus.CONFLICT,
                    "idempotency_conflict",
                    "Idempotency-Key already refers to different trace content.",
                )
            IngestOutcome.Unauthorized ->
                error(HttpStatus.UNAUTHORIZED, "unauthorized", "Valid ingest credentials are required.")
        }
    }

    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<Any> = ResponseEntity.status(status).body(ErrorResponse(code, message))

    private companion object {
        const val TRACE_MEDIA_TYPE = "application/vnd.reprotrail.trace+json"
    }
}

internal data class ErrorResponse(
    val code: String,
    val message: String,
)

internal data class ValidationErrorResponse(
    val code: String,
    val message: String,
    val issues: List<ValidationIssueResponse>,
)

internal data class ValidationIssueResponse(
    val path: String,
    val phase: String,
    val rule: String,
    val message: String,
)

private fun String?.bearerToken(): String? {
    if (this == null || !startsWith("Bearer ", ignoreCase = true)) return null
    return substring(7).trim().takeIf(String::isNotEmpty)
}

private fun String?.toUuidOrNull(): UUID? = this?.let { runCatching { UUID.fromString(it) }.getOrNull() }
