package dev.reprotrail.server.persistence

import dev.reprotrail.server.access.TraceCatalog
import dev.reprotrail.server.access.TraceArtifactCatalog
import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.access.TraceMetadata
import dev.reprotrail.server.access.TracePage
import dev.reprotrail.server.access.TracePageCursor
import dev.reprotrail.server.access.TraceSearchCriteria
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcTraceCatalog(
    private val jdbc: JdbcClient,
) : TraceCatalog, TraceArtifactCatalog {
    override fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage {
        return search(projectId, TraceSearchCriteria(), cursor, limit)
    }

    override fun search(
        projectId: UUID,
        criteria: TraceSearchCriteria,
        cursor: TracePageCursor?,
        limit: Int,
    ): TracePage {
        val rows =
            jdbc.sql(
                """
                select session_id, schema_version, started_at, ended_at, package_name,
                       capture_mode, action_count, created_at
                from traces
                where project_id = :projectId
                  and storage_state = 'available'
                  and (
                      cast(:query as text) is null
                      or position(lower(cast(:query as text)) in lower(package_name)) > 0
                      or position(lower(cast(:query as text)) in lower(cast(session_id as text))) > 0
                  )
                  and (cast(:packageName as text) is null or package_name = cast(:packageName as text))
                  and (cast(:captureMode as text) is null or capture_mode = cast(:captureMode as text))
                  and (cast(:startedAfter as timestamptz) is null or started_at >= cast(:startedAfter as timestamptz))
                  and (cast(:startedBefore as timestamptz) is null or started_at < cast(:startedBefore as timestamptz))
                  and (
                      cast(:cursorCreatedAt as timestamptz) is null
                      or (created_at, session_id) < (cast(:cursorCreatedAt as timestamptz), cast(:cursorSessionId as uuid))
                  )
                order by created_at desc, session_id desc
                limit :fetchLimit
                """.trimIndent(),
            ).param("projectId", projectId)
                .param("query", criteria.query)
                .param("packageName", criteria.packageName)
                .param("captureMode", criteria.captureMode)
                .param("startedAfter", criteria.startedAfter?.atOffset(ZoneOffset.UTC))
                .param("startedBefore", criteria.startedBefore?.atOffset(ZoneOffset.UTC))
                .param("cursorCreatedAt", cursor?.createdAt?.atOffset(ZoneOffset.UTC))
                .param("cursorSessionId", cursor?.sessionId)
                .param("fetchLimit", limit + 1)
                .query(::mapMetadata)
                .list()
        val items = rows.take(limit)
        return TracePage(
            items = items,
            nextCursor =
                items.lastOrNull()
                    ?.takeIf { rows.size > limit }
                    ?.let { TracePageCursor(it.createdAt, it.sessionId) },
        )
    }

    override fun find(projectId: UUID, sessionId: UUID): TraceMetadata? =
        jdbc.sql(
            """
            select session_id, schema_version, started_at, ended_at, package_name,
                   capture_mode, action_count, created_at
            from traces
            where project_id = :projectId and session_id = :sessionId and storage_state = 'available'
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("sessionId", sessionId)
            .query(::mapMetadata)
            .optional()
            .orElse(null)

    override fun findAvailable(projectId: UUID, sessionId: UUID): TraceArtifactReference? =
        jdbc.sql(
            """
            select object_key
            from traces
            where project_id = :projectId and session_id = :sessionId and storage_state = 'available'
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("sessionId", sessionId)
            .query(String::class.java)
            .optional()
            .map(::TraceArtifactReference)
            .orElse(null)
}

private fun mapMetadata(resultSet: ResultSet, rowNumber: Int): TraceMetadata {
    check(rowNumber >= 0)
    return TraceMetadata(
        sessionId = resultSet.getObject("session_id", UUID::class.java),
        schemaVersion = resultSet.getString("schema_version"),
        startedAt = resultSet.getObject("started_at", OffsetDateTime::class.java).toInstant(),
        endedAt = resultSet.getObject("ended_at", OffsetDateTime::class.java)?.toInstant(),
        packageName = resultSet.getString("package_name"),
        captureMode = resultSet.getString("capture_mode"),
        actionCount = resultSet.getInt("action_count"),
        createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )
}
