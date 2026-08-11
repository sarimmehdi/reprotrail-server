package dev.reprotrail.server.access

import java.time.Clock
import java.util.UUID

internal interface TraceDeletionCatalog {
    fun reserve(projectId: UUID, sessionId: UUID): TraceArtifactReference?

    fun complete(event: TraceAuditEvent)

    fun markFailed(projectId: UUID, sessionId: UUID)
}

internal fun interface TraceArtifactDeleter {
    fun delete(reference: TraceArtifactReference)
}

internal sealed interface TraceDeletionResult {
    data object Deleted : TraceDeletionResult

    data object NotFound : TraceDeletionResult
}

internal fun interface TraceDeleter {
    fun delete(projectId: UUID, sessionId: UUID, actorCredentialId: UUID): TraceDeletionResult
}

internal class DeleteTrace(
    private val catalog: TraceDeletionCatalog,
    private val artifactDeleter: TraceArtifactDeleter,
    private val clock: Clock,
) : TraceDeleter {
    override fun delete(projectId: UUID, sessionId: UUID, actorCredentialId: UUID): TraceDeletionResult {
        val reference = catalog.reserve(projectId, sessionId) ?: return TraceDeletionResult.NotFound
        try {
            artifactDeleter.delete(reference)
        } catch (failure: Exception) {
            catalog.markFailed(projectId, sessionId)
            throw failure
        }
        catalog.complete(
            TraceAuditEvent(
                projectId = projectId,
                traceId = sessionId,
                actorCredentialId = actorCredentialId,
                action = TraceAuditAction.Deleted,
                occurredAt = clock.instant(),
            ),
        )
        return TraceDeletionResult.Deleted
    }
}
