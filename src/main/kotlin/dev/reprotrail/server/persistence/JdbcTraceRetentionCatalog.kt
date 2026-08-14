package dev.reprotrail.server.persistence

import dev.reprotrail.server.retention.RetainedTraceIdentity
import dev.reprotrail.server.retention.TraceRetentionCatalog
import java.time.Instant
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcTraceRetentionCatalog(
    private val jdbc: JdbcClient,
) : TraceRetentionCatalog {
    override fun findExpired(
        asOf: Instant,
        defaultRetainFor: Duration,
        limit: Int,
    ): List<RetainedTraceIdentity> =
        jdbc.sql(
            """
            select traces.project_id, traces.session_id
            from traces
            left join project_retention_policies policies on policies.project_id = traces.project_id
            where traces.storage_state = 'available'
              and traces.created_at < :asOf - make_interval(
                  secs => cast(coalesce(policies.retain_for_days * 86400, :defaultRetentionSeconds) as double precision)
              )
            order by traces.created_at, traces.project_id, traces.session_id
            limit :limit
            """.trimIndent(),
        ).param("asOf", asOf.atOffset(ZoneOffset.UTC))
            .param("defaultRetentionSeconds", defaultRetainFor.seconds)
            .param("limit", limit)
            .query { resultSet, _ ->
                RetainedTraceIdentity(
                    projectId = resultSet.getObject("project_id", UUID::class.java),
                    sessionId = resultSet.getObject("session_id", UUID::class.java),
                )
            }.list()
}
