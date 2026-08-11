package dev.reprotrail.server.access

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DownloadTraceTest {
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val traceId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")
    private val credentialId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f402")
    private val reference = TraceArtifactReference("projects/$projectId/traces/$traceId.json")
    private val catalog = RecordingArtifactCatalog(reference)
    private val reader = RecordingReader("{\"trace\":true}".encodeToByteArray())
    private val auditLog = RecordingAuditLog()
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val downloader = DownloadTrace(catalog, reader, auditLog, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `download returns a defensive copy and audits the developer read`() {
        val result = downloader.download(projectId, traceId, credentialId) as TraceDownloadResult.Found

        assertArrayEquals("{\"trace\":true}".encodeToByteArray(), result.content)
        assertEquals(
            TraceAuditEvent(projectId, traceId, credentialId, TraceAuditAction.Downloaded, now),
            auditLog.event,
        )
    }

    @Test
    fun `missing metadata or object remains indistinguishable and unaudited`() {
        catalog.reference = null
        assertEquals(TraceDownloadResult.NotFound, downloader.download(projectId, traceId, credentialId))

        catalog.reference = reference
        reader.content = null
        assertEquals(TraceDownloadResult.NotFound, downloader.download(projectId, traceId, credentialId))
        assertEquals(null, auditLog.event)
    }

    private class RecordingArtifactCatalog(var reference: TraceArtifactReference?) : TraceArtifactCatalog {
        override fun findAvailable(projectId: UUID, sessionId: UUID): TraceArtifactReference? = reference
    }

    private class RecordingReader(var content: ByteArray?) : TraceArtifactReader {
        override fun read(reference: TraceArtifactReference): ByteArray? = content
    }

    private class RecordingAuditLog : TraceAuditLog {
        var event: TraceAuditEvent? = null

        override fun append(event: TraceAuditEvent) {
            this.event = event
        }
    }
}
