package dev.reprotrail.server.replay

import dev.reprotrail.server.access.TraceArtifactCatalog
import dev.reprotrail.server.access.TraceArtifactReader
import dev.reprotrail.server.access.TraceArtifactReference
import java.time.Clock
import java.util.UUID

internal enum class ReplayInputKind {
    TRACE,
    APPLICATION,
}

internal sealed interface ReplayInputDownloadResult {
    data class Found(val content: ByteArray) : ReplayInputDownloadResult

    data object NotFound : ReplayInputDownloadResult
}

internal class DownloadReplayInput(
    private val leases: ReplayLeaseStore,
    private val traces: TraceArtifactCatalog,
    private val applications: ApplicationArtifactCatalog,
    private val reader: TraceArtifactReader,
    private val clock: Clock,
) {
    operator fun invoke(
        projectId: UUID,
        workerCredentialId: UUID,
        jobId: UUID,
        leaseId: UUID,
        kind: ReplayInputKind,
    ): ReplayInputDownloadResult {
        val lease = leases.findActive(projectId, workerCredentialId, jobId, leaseId, clock.instant())
            ?: return ReplayInputDownloadResult.NotFound
        val reference =
            when (kind) {
                ReplayInputKind.TRACE -> traces.findAvailable(projectId, lease.traceId)
                ReplayInputKind.APPLICATION ->
                    applications.findArtifact(projectId, lease.applicationArtifactId)?.let { TraceArtifactReference(it.objectKey) }
            } ?: return ReplayInputDownloadResult.NotFound
        return reader.read(reference)?.let(ReplayInputDownloadResult::Found) ?: ReplayInputDownloadResult.NotFound
    }
}
