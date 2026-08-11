package dev.reprotrail.server.persistence

import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.reconciliation.ReconciliationCandidate
import dev.reprotrail.server.reconciliation.ReconciliationState
import dev.reprotrail.server.reconciliation.TraceReconciliationCatalog
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcTraceReconciliationCatalog(
    private val jdbc: JdbcClient,
) : TraceReconciliationCatalog {
    override fun findStale(updatedBefore: Instant, limit: Int): List<ReconciliationCandidate> =
        jdbc.sql(
            """
            select project_id, session_id, object_key, content_sha256, storage_state
            from traces
            where storage_state in ('pending', 'failed', 'deleting', 'delete_failed')
              and updated_at < :updatedBefore
            order by updated_at, project_id, session_id
            limit :limit
            """.trimIndent(),
        ).param("updatedBefore", updatedBefore.atOffset(ZoneOffset.UTC))
            .param("limit", limit)
            .query { resultSet, _ ->
                ReconciliationCandidate(
                    projectId = resultSet.getObject("project_id", UUID::class.java),
                    sessionId = resultSet.getObject("session_id", UUID::class.java),
                    reference = TraceArtifactReference(resultSet.getString("object_key")),
                    contentSha256 = resultSet.getBytes("content_sha256"),
                    state = resultSet.getString("storage_state").toReconciliationState(),
                )
            }.list()

    override fun markAvailable(projectId: UUID, sessionId: UUID) {
        updateState(projectId, sessionId, "available", setOf("pending", "failed"))
    }

    override fun markFailed(projectId: UUID, sessionId: UUID) {
        val updated =
            jdbc.sql(
                """
                update traces
                set storage_state = case
                        when storage_state in ('deleting', 'delete_failed') then 'delete_failed'
                        else 'failed'
                    end,
                    updated_at = current_timestamp
                where project_id = :projectId and session_id = :sessionId
                """.trimIndent(),
            ).param("projectId", projectId)
                .param("sessionId", sessionId)
                .update()
        check(updated <= 1) { "More than one trace matched reconciliation state." }
    }

    private fun updateState(projectId: UUID, sessionId: UUID, state: String, expectedStates: Set<String>) {
        val updated =
            jdbc.sql(
                """
                update traces
                set storage_state = :state, updated_at = current_timestamp
                where project_id = :projectId and session_id = :sessionId and storage_state in (:expectedStates)
                """.trimIndent(),
            ).param("state", state)
                .param("projectId", projectId)
                .param("sessionId", sessionId)
                .param("expectedStates", expectedStates)
                .update()
        check(updated <= 1) { "More than one trace matched reconciliation state." }
    }
}

private fun String.toReconciliationState(): ReconciliationState =
    when (this) {
        "pending" -> ReconciliationState.Pending
        "failed" -> ReconciliationState.Failed
        "deleting" -> ReconciliationState.Deleting
        "delete_failed" -> ReconciliationState.DeleteFailed
        else -> error("Unknown reconciliation storage state.")
    }
