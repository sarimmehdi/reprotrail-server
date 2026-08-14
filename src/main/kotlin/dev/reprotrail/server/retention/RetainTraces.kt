package dev.reprotrail.server.retention

import dev.reprotrail.server.access.TraceAuditAction
import dev.reprotrail.server.access.TraceDeleter
import dev.reprotrail.server.access.TraceDeletionResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class RetainedTraceIdentity(
    val projectId: UUID,
    val sessionId: UUID,
)

internal fun interface TraceRetentionCatalog {
    fun findExpired(asOf: Instant, defaultRetainFor: Duration, limit: Int): List<RetainedTraceIdentity>
}

internal data class TraceRetentionReport(
    val examined: Int,
    val deleted: Int,
    val alreadyMissing: Int,
    val failed: Int,
)

internal fun interface TraceRetentionRunner {
    fun run(retainFor: Duration, batchSize: Int): TraceRetentionReport
}

internal class RetainTraces(
    private val catalog: TraceRetentionCatalog,
    private val deleter: TraceDeleter,
    private val clock: Clock,
) : TraceRetentionRunner {
    override fun run(retainFor: Duration, batchSize: Int): TraceRetentionReport {
        require(!retainFor.isZero && !retainFor.isNegative) { "Trace retention duration must be positive." }
        require(batchSize in 1..MAXIMUM_BATCH_SIZE) {
            "Trace retention batch size must be between 1 and $MAXIMUM_BATCH_SIZE."
        }
        var deleted = 0
        var alreadyMissing = 0
        var failed = 0
        val candidates = catalog.findExpired(clock.instant(), retainFor, batchSize)
        candidates.forEach { candidate ->
            try {
                when (
                    deleter.delete(
                        candidate.projectId,
                        candidate.sessionId,
                        actorCredentialId = null,
                        auditAction = TraceAuditAction.RetentionDeleted,
                    )
                ) {
                    TraceDeletionResult.Deleted -> deleted += 1
                    TraceDeletionResult.NotFound -> alreadyMissing += 1
                }
            } catch (_: Exception) {
                failed += 1
            }
        }
        return TraceRetentionReport(candidates.size, deleted, alreadyMissing, failed)
    }

    private companion object {
        const val MAXIMUM_BATCH_SIZE = 1_000
    }
}
