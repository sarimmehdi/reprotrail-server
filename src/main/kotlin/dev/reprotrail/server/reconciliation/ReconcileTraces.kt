package dev.reprotrail.server.reconciliation

import dev.reprotrail.server.access.TraceArtifactDeleter
import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.access.TraceAuditAction
import dev.reprotrail.server.access.TraceAuditEvent
import dev.reprotrail.server.access.TraceDeletionCatalog
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class ReconciliationState {
    Pending,
    Failed,
    Deleting,
    DeleteFailed,
}

internal data class ReconciliationCandidate(
    val projectId: UUID,
    val sessionId: UUID,
    val reference: TraceArtifactReference,
    val contentSha256: ByteArray,
    val state: ReconciliationState,
)

internal enum class TraceArtifactInspection {
    Matching,
    Missing,
    Conflict,
}

internal fun interface TraceArtifactInspector {
    fun inspect(reference: TraceArtifactReference, expectedSha256: ByteArray): TraceArtifactInspection
}

internal interface TraceReconciliationCatalog {
    fun findStale(updatedBefore: Instant, limit: Int): List<ReconciliationCandidate>

    fun markAvailable(projectId: UUID, sessionId: UUID)

    fun markFailed(projectId: UUID, sessionId: UUID)
}

internal data class TraceReconciliationReport(
    val examined: Int,
    val restoredAvailable: Int,
    val confirmedFailed: Int,
    val completedDeletions: Int,
    val errors: Int,
)

internal fun interface TraceReconciliationRunner {
    fun run(staleAfter: Duration, batchSize: Int): TraceReconciliationReport
}

internal class ReconcileTraces(
    private val catalog: TraceReconciliationCatalog,
    private val inspector: TraceArtifactInspector,
    private val artifactDeleter: TraceArtifactDeleter,
    private val deletionCatalog: TraceDeletionCatalog,
    private val clock: Clock,
) : TraceReconciliationRunner {
    override fun run(staleAfter: Duration, batchSize: Int): TraceReconciliationReport {
        require(!staleAfter.isZero && !staleAfter.isNegative) { "Reconciliation stale duration must be positive." }
        require(batchSize in 1..MAXIMUM_BATCH_SIZE) {
            "Reconciliation batch size must be between 1 and $MAXIMUM_BATCH_SIZE."
        }
        val candidates = catalog.findStale(clock.instant().minus(staleAfter), batchSize)
        var restoredAvailable = 0
        var confirmedFailed = 0
        var completedDeletions = 0
        var errors = 0
        candidates.forEach { candidate ->
            try {
                when (candidate.state) {
                    ReconciliationState.Pending,
                    ReconciliationState.Failed,
                    -> when (inspector.inspect(candidate.reference, candidate.contentSha256)) {
                        TraceArtifactInspection.Matching -> {
                            catalog.markAvailable(candidate.projectId, candidate.sessionId)
                            restoredAvailable += 1
                        }
                        TraceArtifactInspection.Missing,
                        TraceArtifactInspection.Conflict,
                        -> {
                            catalog.markFailed(candidate.projectId, candidate.sessionId)
                            confirmedFailed += 1
                        }
                    }
                    ReconciliationState.Deleting -> {
                        completeDeletion(candidate)
                        completedDeletions += 1
                    }
                    ReconciliationState.DeleteFailed -> {
                        val reserved = deletionCatalog.reserve(candidate.projectId, candidate.sessionId)
                        if (reserved != null) {
                            completeDeletion(candidate.copy(reference = reserved))
                            completedDeletions += 1
                        }
                    }
                }
            } catch (_: Exception) {
                catalog.markFailed(candidate.projectId, candidate.sessionId)
                errors += 1
            }
        }
        return TraceReconciliationReport(
            examined = candidates.size,
            restoredAvailable = restoredAvailable,
            confirmedFailed = confirmedFailed,
            completedDeletions = completedDeletions,
            errors = errors,
        )
    }

    private fun completeDeletion(candidate: ReconciliationCandidate) {
        artifactDeleter.delete(candidate.reference)
        deletionCatalog.complete(
            TraceAuditEvent(
                projectId = candidate.projectId,
                traceId = candidate.sessionId,
                actorCredentialId = null,
                action = TraceAuditAction.ReconciledDeleted,
                occurredAt = clock.instant(),
            ),
        )
    }

    private companion object {
        const val MAXIMUM_BATCH_SIZE = 1_000
    }
}
