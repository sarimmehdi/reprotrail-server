package dev.reprotrail.server.access

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/projects/{projectId}/traces")
internal class TraceAccessController(
    private val browser: TraceBrowser,
) {
    @GetMapping
    fun list(
        @PathVariable projectId: UUID,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<Any> {
        if (limit !in 1..MAXIMUM_PAGE_SIZE) {
            return error(HttpStatus.BAD_REQUEST, "invalid_limit", "Limit must be between 1 and $MAXIMUM_PAGE_SIZE.")
        }
        val decodedCursor = cursor?.let(::decodeCursor)
        if (cursor != null && decodedCursor == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_cursor", "Cursor is invalid or malformed.")
        }
        val page = browser.list(projectId, decodedCursor, limit)
        return ResponseEntity.ok(
            TraceListResponse(
                items = page.items.map(TraceMetadata::toResponse),
                nextCursor = page.nextCursor?.let(::encodeCursor),
            ),
        )
    }

    @GetMapping("/{traceId}")
    fun metadata(
        @PathVariable projectId: UUID,
        @PathVariable traceId: UUID,
    ): ResponseEntity<Any> =
        browser.find(projectId, traceId)?.let { ResponseEntity.ok(it.toResponse()) }
            ?: error(HttpStatus.NOT_FOUND, "trace_not_found", "Trace was not found.")

    private fun error(status: HttpStatus, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(TraceAccessErrorResponse(code, message))

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100
    }
}

internal data class TraceListResponse(
    val items: List<TraceMetadataResponse>,
    val nextCursor: String?,
)

internal data class TraceMetadataResponse(
    val sessionId: UUID,
    val schemaVersion: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val packageName: String,
    val captureMode: String,
    val actionCount: Int,
    val createdAt: Instant,
)

internal data class TraceAccessErrorResponse(
    val code: String,
    val message: String,
)

private fun TraceMetadata.toResponse(): TraceMetadataResponse =
    TraceMetadataResponse(
        sessionId = sessionId,
        schemaVersion = schemaVersion,
        startedAt = startedAt,
        endedAt = endedAt,
        packageName = packageName,
        captureMode = captureMode,
        actionCount = actionCount,
        createdAt = createdAt,
    )

private fun encodeCursor(cursor: TracePageCursor): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        "${cursor.createdAt}|${cursor.sessionId}".toByteArray(StandardCharsets.UTF_8),
    )

private fun decodeCursor(value: String): TracePageCursor? =
    runCatching {
        val decoded = Base64.getUrlDecoder().decode(value).toString(StandardCharsets.UTF_8)
        val separator = decoded.lastIndexOf('|')
        require(separator > 0 && separator < decoded.lastIndex)
        TracePageCursor(
            createdAt = Instant.parse(decoded.substring(0, separator)),
            sessionId = UUID.fromString(decoded.substring(separator + 1)),
        )
    }.getOrNull()
