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

internal data class TraceSearchCriteria(
    val query: String? = null,
    val packageName: String? = null,
    val captureMode: String? = null,
    val startedAfter: Instant? = null,
    val startedBefore: Instant? = null,
) {
    init {
        require(query == null || query.length <= MAXIMUM_QUERY_LENGTH) {
            "Search query must not exceed $MAXIMUM_QUERY_LENGTH characters."
        }
        require(packageName == null || packageName.length <= MAXIMUM_PACKAGE_NAME_LENGTH) {
            "Package name must not exceed $MAXIMUM_PACKAGE_NAME_LENGTH characters."
        }
        require(captureMode == null || captureMode.length <= MAXIMUM_CAPTURE_MODE_LENGTH) {
            "Capture mode must not exceed $MAXIMUM_CAPTURE_MODE_LENGTH characters."
        }
        require(startedAfter == null || startedBefore == null || startedAfter < startedBefore) {
            "Search start must be before search end."
        }
    }

    private companion object {
        const val MAXIMUM_QUERY_LENGTH = 200
        const val MAXIMUM_PACKAGE_NAME_LENGTH = 255
        const val MAXIMUM_CAPTURE_MODE_LENGTH = 32
    }
}

internal interface TraceCatalog {
    fun search(
        projectId: UUID,
        criteria: TraceSearchCriteria,
        cursor: TracePageCursor?,
        limit: Int,
    ): TracePage

    fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage =
        search(projectId, TraceSearchCriteria(), cursor, limit)

    fun find(projectId: UUID, sessionId: UUID): TraceMetadata?
}

internal interface TraceBrowser {
    fun search(
        projectId: UUID,
        criteria: TraceSearchCriteria,
        cursor: TracePageCursor?,
        limit: Int,
    ): TracePage

    fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage =
        search(projectId, TraceSearchCriteria(), cursor, limit)

    fun find(projectId: UUID, sessionId: UUID): TraceMetadata?
}

internal class BrowseTraces(
    private val catalog: TraceCatalog,
) : TraceBrowser {
    override fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage {
        return search(projectId, TraceSearchCriteria(), cursor, limit)
    }

    override fun search(
        projectId: UUID,
        criteria: TraceSearchCriteria,
        cursor: TracePageCursor?,
        limit: Int,
    ): TracePage {
        require(limit in 1..MAXIMUM_PAGE_SIZE) { "Trace page size must be between 1 and $MAXIMUM_PAGE_SIZE." }
        return catalog.search(projectId, criteria, cursor, limit)
    }

    override fun find(projectId: UUID, sessionId: UUID): TraceMetadata? = catalog.find(projectId, sessionId)

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100
    }
}
