package dev.reprotrail.server.persistence

import dev.reprotrail.server.access.TraceCatalog
import dev.reprotrail.server.access.TraceMetadata
import dev.reprotrail.server.access.TracePage
import dev.reprotrail.server.access.TracePageCursor
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcTraceCatalog(
    private val jdbc: JdbcClient,
) : TraceCatalog {
    override fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage {
        val rows =
            jdbc.sql(
                """
                select session_id, schema_version, started_at, ended_at, package_name,
                       capture_mode, action_count, created_at
                from traces
                where project_id = :projectId
                  and storage_state = 'available'
                  and (
                      cast(:cursorCreatedAt as timestamptz) is null
                      or (created_at, session_id) < (cast(:cursorCreatedAt as timestamptz), cast(:cursorSessionId as uuid))
                  )
                order by created_at desc, session_id desc
                limit :fetchLimit
                """.trimIndent(),
            ).param("projectId", projectId)
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
