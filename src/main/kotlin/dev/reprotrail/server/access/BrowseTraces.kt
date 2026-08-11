package dev.reprotrail.server.access

import java.time.Instant
import java.util.UUID

internal data class TraceMetadata(
    val sessionId: UUID,
    val schemaVersion: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val packageName: String,
    val captureMode: String,
    val actionCount: Int,
    val createdAt: Instant,
)

internal data class TracePageCursor(
    val createdAt: Instant,
    val sessionId: UUID,
)

internal data class TracePage(
    val items: List<TraceMetadata>,
    val nextCursor: TracePageCursor?,
)

internal interface TraceCatalog {
    fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage

    fun find(projectId: UUID, sessionId: UUID): TraceMetadata?
}

internal interface TraceBrowser {
    fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage

    fun find(projectId: UUID, sessionId: UUID): TraceMetadata?
}

internal class BrowseTraces(
    private val catalog: TraceCatalog,
) : TraceBrowser {
    override fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage {
        require(limit in 1..MAXIMUM_PAGE_SIZE) { "Trace page size must be between 1 and $MAXIMUM_PAGE_SIZE." }
        return catalog.list(projectId, cursor, limit)
    }

    override fun find(projectId: UUID, sessionId: UUID): TraceMetadata? = catalog.find(projectId, sessionId)

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100
    }
}
