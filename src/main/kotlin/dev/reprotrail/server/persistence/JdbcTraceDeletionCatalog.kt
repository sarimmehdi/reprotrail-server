package dev.reprotrail.server.persistence

import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.access.TraceAuditEvent
import dev.reprotrail.server.access.TraceDeletionCatalog
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

internal class JdbcTraceDeletionCatalog(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) : TraceDeletionCatalog {
    override fun reserve(projectId: UUID, sessionId: UUID): TraceArtifactReference? =
        jdbc.sql(
            """
            update traces
            set storage_state = 'deleting', updated_at = current_timestamp
            where project_id = :projectId
              and session_id = :sessionId
              and storage_state in ('available', 'delete_failed')
            returning object_key
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("sessionId", sessionId)
            .query(String::class.java)
            .optional()
            .map(::TraceArtifactReference)
            .orElse(null)

    override fun complete(event: TraceAuditEvent) {
        val completed =
            transactions.execute {
                val deleted =
                    jdbc.sql(
                        """
                        delete from traces
                        where project_id = :projectId and session_id = :sessionId and storage_state = 'deleting'
                        """.trimIndent(),
                    ).param("projectId", event.projectId)
                        .param("sessionId", event.traceId)
                        .update()
                check(deleted == 1) { "Reserved trace deletion is missing." }
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
                true
            }
        check(completed == true) { "Trace deletion transaction did not execute." }
    }

    override fun markFailed(projectId: UUID, sessionId: UUID) {
        jdbc.sql(
            """
            update traces
            set storage_state = 'delete_failed', updated_at = current_timestamp
            where project_id = :projectId and session_id = :sessionId and storage_state = 'deleting'
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("sessionId", sessionId)
            .update()
    }
}
