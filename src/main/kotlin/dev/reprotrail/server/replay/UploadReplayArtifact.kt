package dev.reprotrail.server.replay

import dev.reprotrail.server.access.TraceArtifactReference
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

internal fun interface ReplayArtifactVerifier {
    fun verify(projectId: UUID, jobId: UUID, artifact: ReplayArtifactReceipt): Boolean
}

internal data class ReplayArtifactContentWrite(
    val objectKey: String,
    val content: ByteArray,
    val contentSha256: ByteArray,
    val contentType: String,
)

internal enum class ReplayArtifactContentWriteResult {
    STORED,
    ALREADY_EXISTS,
    CONFLICT,
}

internal fun interface ReplayArtifactContentStore {
    fun putIfAbsent(write: ReplayArtifactContentWrite): ReplayArtifactContentWriteResult
}

internal sealed interface ReplayArtifactUploadResult {
    data class Stored(val receipt: ReplayArtifactReceipt) : ReplayArtifactUploadResult

    data object NotActive : ReplayArtifactUploadResult

    data object TooLarge : ReplayArtifactUploadResult

    data object Conflict : ReplayArtifactUploadResult
}

internal class UploadReplayArtifact(
    private val leases: ReplayLeaseStore,
    private val contentStore: ReplayArtifactContentStore,
    private val clock: Clock,
    private val maxArtifactBytes: Long,
) {
    init {
        require(maxArtifactBytes in 1..MAXIMUM_ARTIFACT_BYTES) { "Artifact size limit is invalid." }
    }

    operator fun invoke(
        projectId: UUID,
        workerCredentialId: UUID,
        jobId: UUID,
        leaseId: UUID,
        kind: ReplayArtifactKind,
        name: String,
        content: ByteArray,
    ): ReplayArtifactUploadResult {
        if (content.size.toLong() > maxArtifactBytes) return ReplayArtifactUploadResult.TooLarge
        leases.findActive(projectId, workerCredentialId, jobId, leaseId, clock.instant())
            ?: return ReplayArtifactUploadResult.NotActive
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        val receipt = ReplayArtifactReceipt(kind, name, digest.toHex(), content.size.toLong())
        val write =
            ReplayArtifactContentWrite(
                objectKey = replayArtifactKey(projectId, jobId, name),
                content = content.copyOf(),
                contentSha256 = digest,
                contentType = "application/octet-stream",
            )
        return when (contentStore.putIfAbsent(write)) {
            ReplayArtifactContentWriteResult.STORED, ReplayArtifactContentWriteResult.ALREADY_EXISTS ->
                ReplayArtifactUploadResult.Stored(receipt)
            ReplayArtifactContentWriteResult.CONFLICT -> ReplayArtifactUploadResult.Conflict
        }
    }

    private companion object {
        const val MAXIMUM_ARTIFACT_BYTES = 100L * 1024 * 1024
    }
}

internal fun replayArtifactReference(projectId: UUID, jobId: UUID, name: String) =
    TraceArtifactReference(replayArtifactKey(projectId, jobId, name))

private fun replayArtifactKey(projectId: UUID, jobId: UUID, name: String) =
    "projects/$projectId/replays/$jobId/$name"

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
