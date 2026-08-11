package dev.reprotrail.server.access

import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class TraceArtifactReference(
    val objectKey: String,
)

internal fun interface TraceArtifactCatalog {
    fun findAvailable(projectId: UUID, sessionId: UUID): TraceArtifactReference?
}

internal fun interface TraceArtifactReader {
    fun read(reference: TraceArtifactReference): ByteArray?
}

internal enum class TraceAuditAction {
    Downloaded,
    Deleted,
    RetentionDeleted,
    ReconciledDeleted,
}

internal data class TraceAuditEvent(
    val projectId: UUID,
    val traceId: UUID,
    val actorCredentialId: UUID?,
    val action: TraceAuditAction,
    val occurredAt: Instant,
)

internal fun interface TraceAuditLog {
    fun append(event: TraceAuditEvent)
}

internal sealed interface TraceDownloadResult {
    data class Found(val content: ByteArray) : TraceDownloadResult

    data object NotFound : TraceDownloadResult
}

internal fun interface TraceDownloader {
    fun download(projectId: UUID, sessionId: UUID, actorCredentialId: UUID): TraceDownloadResult
}

internal class DownloadTrace(
    private val catalog: TraceArtifactCatalog,
    private val reader: TraceArtifactReader,
    private val auditLog: TraceAuditLog,
    private val clock: Clock,
) : TraceDownloader {
    override fun download(projectId: UUID, sessionId: UUID, actorCredentialId: UUID): TraceDownloadResult {
        val reference = catalog.findAvailable(projectId, sessionId) ?: return TraceDownloadResult.NotFound
        val content = reader.read(reference) ?: return TraceDownloadResult.NotFound
        auditLog.append(
            TraceAuditEvent(
                projectId = projectId,
                traceId = sessionId,
                actorCredentialId = actorCredentialId,
                action = TraceAuditAction.Downloaded,
                occurredAt = clock.instant(),
            ),
        )
        return TraceDownloadResult.Found(content.copyOf())
    }
}
