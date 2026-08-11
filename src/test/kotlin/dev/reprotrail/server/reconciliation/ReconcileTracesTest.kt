package dev.reprotrail.server.reconciliation

import dev.reprotrail.server.access.TraceArtifactDeleter
import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.access.TraceAuditAction
import dev.reprotrail.server.access.TraceAuditEvent
import dev.reprotrail.server.access.TraceDeletionCatalog
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReconcileTracesTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val pendingId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")
    private val failedId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f442")
    private val deletingId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f443")
    private val digest = ByteArray(32) { it.toByte() }
    private val candidates =
        listOf(
            candidate(pendingId, ReconciliationState.Pending),
            candidate(failedId, ReconciliationState.Failed),
            candidate(deletingId, ReconciliationState.Deleting),
        )
    private val catalog = RecordingCatalog(candidates)
    private val inspector = RecordingInspector()
    private val artifactDeleter = RecordingDeleter()
    private val deletionCatalog = RecordingDeletionCatalog()
    private val runner =
        ReconcileTraces(catalog, inspector, artifactDeleter, deletionCatalog, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `reconciliation repairs ingestion and completes interrupted deletion`() {
        inspector.results[pendingId] = TraceArtifactInspection.Matching
        inspector.results[failedId] = TraceArtifactInspection.Missing

        val report = runner.run(Duration.ofMinutes(15), 100)

        assertEquals(TraceReconciliationReport(3, 1, 1, 1, 0), report)
        assertEquals(setOf(pendingId), catalog.available)
        assertEquals(setOf(failedId), catalog.failed)
        assertEquals(setOf(deletingId), artifactDeleter.deleted)
        assertEquals(
            TraceAuditEvent(projectId, deletingId, null, TraceAuditAction.ReconciledDeleted, now),
            deletionCatalog.completed.single(),
        )
        assertEquals(now.minus(Duration.ofMinutes(15)) to 100, catalog.lastQuery)
    }

    @Test
    fun `candidate failure is isolated and leaves retryable state`() {
        catalog.candidates = listOf(candidate(deletingId, ReconciliationState.Deleting))
        artifactDeleter.failures += deletingId

        val report = runner.run(Duration.ofMinutes(15), 100)

        assertEquals(TraceReconciliationReport(1, 0, 0, 0, 1), report)
        assertEquals(setOf(deletingId), catalog.failed)
        assertEquals(emptyList<TraceAuditEvent>(), deletionCatalog.completed)
    }

    private fun candidate(sessionId: UUID, state: ReconciliationState) =
        ReconciliationCandidate(
            projectId = projectId,
            sessionId = sessionId,
            reference = TraceArtifactReference("projects/$projectId/traces/$sessionId.json"),
            contentSha256 = digest,
            state = state,
        )

    private class RecordingCatalog(initialCandidates: List<ReconciliationCandidate>) : TraceReconciliationCatalog {
        var candidates = initialCandidates
        var lastQuery: Pair<Instant, Int>? = null
        val available = mutableSetOf<UUID>()
        val failed = mutableSetOf<UUID>()

        override fun findStale(updatedBefore: Instant, limit: Int): List<ReconciliationCandidate> {
            lastQuery = updatedBefore to limit
            return candidates
        }

        override fun markAvailable(projectId: UUID, sessionId: UUID) {
            available += sessionId
        }

        override fun markFailed(projectId: UUID, sessionId: UUID) {
            failed += sessionId
        }
    }

    private inner class RecordingInspector : TraceArtifactInspector {
        val results = mutableMapOf<UUID, TraceArtifactInspection>()

        override fun inspect(reference: TraceArtifactReference, expectedSha256: ByteArray): TraceArtifactInspection =
            checkNotNull(results[reference.objectKey.substringAfterLast('/').removeSuffix(".json").let(UUID::fromString)])
    }

    private inner class RecordingDeleter : TraceArtifactDeleter {
        val deleted = mutableSetOf<UUID>()
        val failures = mutableSetOf<UUID>()

        override fun delete(reference: TraceArtifactReference) {
            val traceId = reference.objectKey.substringAfterLast('/').removeSuffix(".json").let(UUID::fromString)
            if (traceId in failures) error("storage unavailable")
            deleted += traceId
        }
    }

    private class RecordingDeletionCatalog : TraceDeletionCatalog {
        val completed = mutableListOf<TraceAuditEvent>()

        override fun reserve(projectId: UUID, sessionId: UUID): TraceArtifactReference? = null

        override fun complete(event: TraceAuditEvent) {
            completed += event
        }

        override fun markFailed(projectId: UUID, sessionId: UUID) = Unit
    }
}
