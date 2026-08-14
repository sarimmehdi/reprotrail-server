package dev.reprotrail.server.replay

import dev.reprotrail.server.access.TraceArtifactReader
import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.access.TraceAuditAction
import dev.reprotrail.server.access.TraceAuditEvent
import dev.reprotrail.server.access.TraceAuditLog
import java.time.Clock
import java.util.UUID

internal data class ReplayArtifactDownloadReference(
    val traceId: UUID,
    val artifact: ReplayJobArtifact,
    val reference: TraceArtifactReference,
)

internal fun interface ReplayArtifactCatalog {
    fun findReplayArtifact(projectId: UUID, jobId: UUID, name: String): ReplayArtifactDownloadReference?
}

internal sealed interface ReplayArtifactDownloadResult {
    data class Found(val artifact: ReplayJobArtifact, val content: ByteArray) : ReplayArtifactDownloadResult

    data object NotFound : ReplayArtifactDownloadResult
}

internal fun interface ReplayArtifactDownloader {
    fun download(
        projectId: UUID,
        jobId: UUID,
        name: String,
        actorCredentialId: UUID,
    ): ReplayArtifactDownloadResult
}

internal class DownloadReplayArtifact(
    private val catalog: ReplayArtifactCatalog,
    private val reader: TraceArtifactReader,
    private val auditLog: TraceAuditLog,
    private val clock: Clock,
) : ReplayArtifactDownloader {
    override fun download(
        projectId: UUID,
        jobId: UUID,
        name: String,
        actorCredentialId: UUID,
    ): ReplayArtifactDownloadResult {
        val artifact = catalog.findReplayArtifact(projectId, jobId, name) ?: return ReplayArtifactDownloadResult.NotFound
        val content = reader.read(artifact.reference) ?: return ReplayArtifactDownloadResult.NotFound
        auditLog.append(
            TraceAuditEvent(
                projectId = projectId,
                traceId = artifact.traceId,
                actorCredentialId = actorCredentialId,
                action = TraceAuditAction.ReplayArtifactDownloaded,
                occurredAt = clock.instant(),
                replayJobId = jobId,
            ),
        )
        return ReplayArtifactDownloadResult.Found(artifact.artifact, content.copyOf())
    }
}
