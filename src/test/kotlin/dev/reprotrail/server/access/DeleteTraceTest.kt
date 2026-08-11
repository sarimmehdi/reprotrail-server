package dev.reprotrail.server.access

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DeleteTraceTest {
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val traceId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")
    private val credentialId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f402")
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val reference = TraceArtifactReference("projects/$projectId/traces/$traceId.json")
    private val catalog = RecordingDeletionCatalog(reference)
    private val artifactDeleter = RecordingArtifactDeleter()
    private val deleter = DeleteTrace(catalog, artifactDeleter, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `deletion removes content before atomically completing metadata and audit`() {
        assertEquals(
            TraceDeletionResult.Deleted,
            deleter.delete(projectId, traceId, credentialId, TraceAuditAction.Deleted),
        )

        assertEquals(reference, artifactDeleter.deleted)
        assertEquals(
            TraceAuditEvent(projectId, traceId, credentialId, TraceAuditAction.Deleted, now),
            catalog.completed,
        )
    }

    @Test
    fun `missing tenant trace does not touch object storage`() {
        catalog.reference = null

        assertEquals(
            TraceDeletionResult.NotFound,
            deleter.delete(projectId, traceId, credentialId, TraceAuditAction.Deleted),
        )
        assertEquals(null, artifactDeleter.deleted)
    }

    @Test
    fun `storage failure leaves a retryable deletion state and no audit tombstone`() {
        artifactDeleter.failure = IllegalStateException("storage unavailable")

        assertThrows(IllegalStateException::class.java) {
            deleter.delete(projectId, traceId, credentialId, TraceAuditAction.Deleted)
        }
        assertEquals(projectId to traceId, catalog.failed)
        assertEquals(null, catalog.completed)
    }

    private class RecordingDeletionCatalog(var reference: TraceArtifactReference?) : TraceDeletionCatalog {
        var completed: TraceAuditEvent? = null
        var failed: Pair<UUID, UUID>? = null

        override fun reserve(projectId: UUID, sessionId: UUID): TraceArtifactReference? = reference

        override fun complete(event: TraceAuditEvent) {
            completed = event
        }

        override fun markFailed(projectId: UUID, sessionId: UUID) {
            failed = projectId to sessionId
        }
    }

    private class RecordingArtifactDeleter : TraceArtifactDeleter {
        var deleted: TraceArtifactReference? = null
        var failure: RuntimeException? = null

        override fun delete(reference: TraceArtifactReference) {
            failure?.let { throw it }
            deleted = reference
        }
    }
}
