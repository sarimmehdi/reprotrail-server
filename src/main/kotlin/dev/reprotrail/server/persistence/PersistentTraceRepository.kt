package dev.reprotrail.server.persistence

import dev.reprotrail.server.contract.ValidatedTraceMetadata
import dev.reprotrail.server.ingest.StoredTrace
import dev.reprotrail.server.ingest.TraceCreateResult
import dev.reprotrail.server.ingest.TraceRepository
import java.security.MessageDigest
import java.util.UUID

internal enum class TraceStorageState {
    Pending,
    Available,
    Failed,
}

internal sealed interface TraceReservation {
    data object Created : TraceReservation

    data class Existing(
        val contentSha256: ByteArray,
        val state: TraceStorageState,
    ) : TraceReservation

    data object Conflict : TraceReservation
}

internal data class TraceMetadataRecord(
    val projectId: UUID,
    val idempotencyKey: UUID,
    val objectKey: String,
    val metadata: ValidatedTraceMetadata,
    val contentSha256: ByteArray,
)

internal interface TraceMetadataStore {
    fun reserve(record: TraceMetadataRecord): TraceReservation

    fun markAvailable(projectId: UUID, sessionId: UUID)

    fun markFailed(projectId: UUID, sessionId: UUID)
}

internal data class TraceContentWrite(
    val objectKey: String,
    val content: ByteArray,
    val contentSha256: ByteArray,
    val contentType: String = "application/vnd.reprotrail.trace+json",
)

internal enum class TraceContentWriteResult {
    Stored,
    AlreadyExists,
    Conflict,
}

internal fun interface TraceContentStore {
    fun putIfAbsent(write: TraceContentWrite): TraceContentWriteResult
}

internal class PersistentTraceRepository(
    private val metadataStore: TraceMetadataStore,
    private val contentStore: TraceContentStore,
) : TraceRepository {
    override fun create(record: StoredTrace): TraceCreateResult {
        val objectKey = "projects/${record.projectId}/traces/${record.metadata.sessionId}.json"
        val reservation =
            metadataStore.reserve(
                TraceMetadataRecord(
                    projectId = record.projectId,
                    idempotencyKey = record.idempotencyKey,
                    objectKey = objectKey,
                    metadata = record.metadata,
                    contentSha256 = record.contentSha256,
                ),
            )
        if (reservation == TraceReservation.Conflict) return TraceCreateResult.Conflict
        if (reservation is TraceReservation.Existing) {
            if (!MessageDigest.isEqual(reservation.contentSha256, record.contentSha256)) {
                return TraceCreateResult.Conflict
            }
            if (reservation.state == TraceStorageState.Available) {
                return TraceCreateResult.AlreadyExists
            }
        }

        val writeResult =
            try {
                contentStore.putIfAbsent(
                    TraceContentWrite(
                        objectKey = objectKey,
                        content = record.content.copyOf(),
                        contentSha256 = record.contentSha256.copyOf(),
                    ),
                )
            } catch (failure: Exception) {
                metadataStore.markFailed(record.projectId, record.metadata.sessionId)
                throw failure
            }

        if (writeResult == TraceContentWriteResult.Conflict) {
            metadataStore.markFailed(record.projectId, record.metadata.sessionId)
            return TraceCreateResult.Conflict
        }
        metadataStore.markAvailable(record.projectId, record.metadata.sessionId)
        return if (reservation == TraceReservation.Created) {
            TraceCreateResult.Created
        } else {
            TraceCreateResult.AlreadyExists
        }
    }
}
