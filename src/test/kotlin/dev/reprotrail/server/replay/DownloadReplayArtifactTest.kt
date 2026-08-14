package dev.reprotrail.server.replay

import dev.reprotrail.server.access.TraceArtifactReader
import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.access.TraceAuditAction
import dev.reprotrail.server.access.TraceAuditEvent
import dev.reprotrail.server.access.TraceAuditLog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DownloadReplayArtifactTest {
    private val projectId = UUID.randomUUID()
    private val traceId = UUID.randomUUID()
    private val jobId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-15T00:00:00Z")
    private val reference =
        ReplayArtifactDownloadReference(
            traceId,
            ReplayJobArtifact(ReplayArtifactKind.MAESTRO_OUTPUT, "commands.json", "a".repeat(64), 2, now),
            TraceArtifactReference("projects/$projectId/replays/$jobId/commands.json"),
        )
    private val catalog = ReplayDownloadCatalog(reference)
    private val audit = RecordingReplayAuditLog()
    private val download =
        DownloadReplayArtifact(
            catalog,
            TraceArtifactReader { byteArrayOf(1, 2) },
            audit,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun `downloads a tenant artifact and audits trace job and developer identity`() {
        val result =
            download.download(projectId, jobId, "commands.json", actorId) as ReplayArtifactDownloadResult.Found

        assertContentEquals(byteArrayOf(1, 2), result.content)
        assertEquals("commands.json", result.artifact.name)
        assertEquals(
            TraceAuditEvent(projectId, traceId, actorId, TraceAuditAction.ReplayArtifactDownloaded, now, jobId),
            audit.event,
        )
    }

    private class ReplayDownloadCatalog(var found: ReplayArtifactDownloadReference?) : ReplayArtifactCatalog {
        override fun findReplayArtifact(projectId: UUID, jobId: UUID, name: String) = found
    }

    private class RecordingReplayAuditLog : TraceAuditLog {
        var event: TraceAuditEvent? = null

        override fun append(event: TraceAuditEvent) {
            this.event = event
        }
    }
}
