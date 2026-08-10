package dev.reprotrail.server.ingest

import dev.reprotrail.server.contract.TraceContractValidator
import dev.reprotrail.server.contract.TraceIssue
import dev.reprotrail.server.contract.TraceValidationResult
import dev.reprotrail.server.contract.ValidatedTraceMetadata
import java.security.MessageDigest
import java.util.UUID

internal fun interface IngestAuthorizer {
    fun isAuthorized(projectId: UUID, token: String): Boolean
}

internal interface TraceRepository {
    fun create(record: StoredTrace): TraceCreateResult
}

internal enum class TraceCreateResult {
    Created,
    AlreadyExists,
    Conflict,
}

internal data class IngestRequest(
    val projectId: UUID,
    val token: String,
    val idempotencyKey: UUID,
    val content: ByteArray,
)

internal data class StoredTrace(
    val projectId: UUID,
    val idempotencyKey: UUID,
    val metadata: ValidatedTraceMetadata,
    val content: ByteArray,
    val contentSha256: ByteArray,
)

internal data class IngestedTrace(
    val sessionId: UUID,
    val schemaVersion: String,
    val actionCount: Int,
)

internal sealed interface IngestOutcome {
    data class Created(val trace: IngestedTrace) : IngestOutcome

    data class AlreadyExists(val trace: IngestedTrace) : IngestOutcome

    data object Unauthorized : IngestOutcome

    data class InvalidTrace(val issues: List<TraceIssue>) : IngestOutcome

    data object IdempotencyMismatch : IngestOutcome

    data object Conflict : IngestOutcome
}

internal class IngestTraceUseCase(
    private val authorizer: IngestAuthorizer,
    private val validator: TraceContractValidator,
    private val repository: TraceRepository,
) {
    fun ingest(request: IngestRequest): IngestOutcome {
        if (!authorizer.isAuthorized(request.projectId, request.token)) {
            return IngestOutcome.Unauthorized
        }

        val validation = validator.validate(request.content.decodeToString())
        if (validation is TraceValidationResult.Invalid) {
            return IngestOutcome.InvalidTrace(validation.issues)
        }
        val metadata = (validation as TraceValidationResult.Valid).metadata
        if (request.idempotencyKey != metadata.sessionId) {
            return IngestOutcome.IdempotencyMismatch
        }

        val summary = IngestedTrace(metadata.sessionId, metadata.schemaVersion, metadata.actionCount)
        val storedTrace =
            StoredTrace(
                projectId = request.projectId,
                idempotencyKey = request.idempotencyKey,
                metadata = metadata,
                content = request.content.copyOf(),
                contentSha256 = MessageDigest.getInstance("SHA-256").digest(request.content),
            )
        return when (repository.create(storedTrace)) {
            TraceCreateResult.Created -> IngestOutcome.Created(summary)
            TraceCreateResult.AlreadyExists -> IngestOutcome.AlreadyExists(summary)
            TraceCreateResult.Conflict -> IngestOutcome.Conflict
        }
    }
}
