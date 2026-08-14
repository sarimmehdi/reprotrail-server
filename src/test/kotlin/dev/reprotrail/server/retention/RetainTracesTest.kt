package dev.reprotrail.server.retention

import dev.reprotrail.server.access.TraceAuditAction
import dev.reprotrail.server.access.TraceDeleter
import dev.reprotrail.server.access.TraceDeletionResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RetainTracesTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val traces =
        listOf(
            RetainedTraceIdentity(projectId, UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")),
            RetainedTraceIdentity(projectId, UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f442")),
            RetainedTraceIdentity(projectId, UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f443")),
        )
    private val catalog = RecordingRetentionCatalog(traces)
    private val deleter = RecordingRetentionDeleter()
    private val runner = RetainTraces(catalog, deleter, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `retention deletes a bounded expired batch with system audit identity`() {
        deleter.results[traces[0].sessionId] = TraceDeletionResult.Deleted
        deleter.results[traces[1].sessionId] = TraceDeletionResult.NotFound
        deleter.failures += traces[2].sessionId

        val report = runner.run(Duration.ofDays(30), 100)

        assertEquals(TraceRetentionReport(examined = 3, deleted = 1, alreadyMissing = 1, failed = 1), report)
        assertEquals(Triple(now, Duration.ofDays(30), 100), catalog.lastQuery)
        assertEquals(setOf(null), deleter.actorIds)
        assertEquals(setOf(TraceAuditAction.RetentionDeleted), deleter.actions)
    }

    @Test
    fun `retention rejects unsafe duration and batch configuration`() {
        assertThrows(IllegalArgumentException::class.java) { runner.run(Duration.ZERO, 100) }
        assertThrows(IllegalArgumentException::class.java) { runner.run(Duration.ofDays(1), 1_001) }
    }

    private class RecordingRetentionCatalog(private val traces: List<RetainedTraceIdentity>) : TraceRetentionCatalog {
        var lastQuery: Triple<Instant, Duration, Int>? = null

        override fun findExpired(asOf: Instant, defaultRetainFor: Duration, limit: Int): List<RetainedTraceIdentity> {
            lastQuery = Triple(asOf, defaultRetainFor, limit)
            return traces
        }
    }

    private class RecordingRetentionDeleter : TraceDeleter {
        val results = mutableMapOf<UUID, TraceDeletionResult>()
        val failures = mutableSetOf<UUID>()
        val actorIds = mutableSetOf<UUID?>()
        val actions = mutableSetOf<TraceAuditAction>()

        override fun delete(
            projectId: UUID,
            sessionId: UUID,
            actorCredentialId: UUID?,
            auditAction: TraceAuditAction,
        ): TraceDeletionResult {
            actorIds += actorCredentialId
            actions += auditAction
            if (sessionId in failures) throw IllegalStateException("storage unavailable")
            return checkNotNull(results[sessionId])
        }
    }
}
