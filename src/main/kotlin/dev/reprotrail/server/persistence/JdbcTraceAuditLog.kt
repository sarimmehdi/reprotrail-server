package dev.reprotrail.server.persistence

import dev.reprotrail.server.access.TraceAuditEvent
import dev.reprotrail.server.access.TraceAuditLog
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcTraceAuditLog(
    private val jdbc: JdbcClient,
) : TraceAuditLog {
    override fun append(event: TraceAuditEvent) {
        jdbc.sql(
            """
            insert into audit_events (id, project_id, trace_id, actor_credential_id, action, occurred_at)
            values (:id, :projectId, :traceId, :actorCredentialId, :action, :occurredAt)
            """.trimIndent(),
        ).param("id", UUID.randomUUID())
            .param("projectId", event.projectId)
            .param("traceId", event.traceId)
            .param("actorCredentialId", event.actorCredentialId)
            .param("action", event.action.name.lowercase())
            .param("occurredAt", event.occurredAt.atOffset(ZoneOffset.UTC))
            .update()
    }
}
