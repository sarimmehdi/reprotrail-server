package dev.reprotrail.server.persistence

import dev.reprotrail.server.retention.RetainedTraceIdentity
import dev.reprotrail.server.retention.TraceRetentionCatalog
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcTraceRetentionCatalog(
    private val jdbc: JdbcClient,
) : TraceRetentionCatalog {
    override fun findExpired(createdBefore: Instant, limit: Int): List<RetainedTraceIdentity> =
        jdbc.sql(
            """
            select project_id, session_id
            from traces
            where storage_state = 'available' and created_at < :createdBefore
            order by created_at, project_id, session_id
            limit :limit
            """.trimIndent(),
        ).param("createdBefore", createdBefore.atOffset(ZoneOffset.UTC))
            .param("limit", limit)
            .query { resultSet, _ ->
                RetainedTraceIdentity(
                    projectId = resultSet.getObject("project_id", UUID::class.java),
                    sessionId = resultSet.getObject("session_id", UUID::class.java),
                )
            }.list()
}
