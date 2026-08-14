package dev.reprotrail.server.replay

import dev.reprotrail.server.access.TraceCatalog
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class ApplicationArtifact(
    val id: UUID,
    val packageName: String,
    val objectKey: String,
)

internal fun interface ApplicationArtifactCatalog {
    fun findArtifact(projectId: UUID, artifactId: UUID): ApplicationArtifact?
}

internal data class CreateReplayJobRequest(
    val projectId: UUID,
    val traceId: UUID,
    val applicationArtifactId: UUID,
    val repetitions: Int,
    val attemptTimeout: Duration,
    val actorCredentialId: UUID,
) {
    init {
        require(repetitions in 1..10) { "Repetitions must be between 1 and 10." }
        require(!attemptTimeout.isZero && !attemptTimeout.isNegative && attemptTimeout <= Duration.ofMinutes(30)) {
            "Attempt timeout must be between zero and 30 minutes."
        }
    }
}

internal enum class ReplayJobState {
    QUEUED,
    LEASED,
    SUCCEEDED,
    FAILED,
}

internal data class ReplayJob(
    val id: UUID,
    val projectId: UUID,
    val traceId: UUID,
    val applicationArtifactId: UUID,
    val packageName: String,
    val repetitions: Int,
    val attemptTimeout: Duration,
    val state: ReplayJobState,
    val createdAt: Instant,
    val attemptCount: Int = 0,
    val maxAttempts: Int = 3,
    val passedRepetitions: Int? = null,
    val failedRepetitions: Int? = null,
    val failureCode: WorkerReplayFailureCode? = null,
    val failureSummary: String? = null,
    val updatedAt: Instant = createdAt,
    val artifacts: List<ReplayJobArtifact> = emptyList(),
)

internal data class ReplayJobArtifact(
    val kind: ReplayArtifactKind,
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)

internal fun interface ReplayJobStore {
    fun create(request: CreateReplayJobRequest, job: ReplayJob): ReplayJob
}

internal interface ReplayJobReader {
    fun findJob(projectId: UUID, jobId: UUID): ReplayJob?

    fun listJobs(projectId: UUID, traceId: UUID, limit: Int): List<ReplayJob>
}

internal sealed interface ReplayJobCreationResult {
    data class Created(val job: ReplayJob) : ReplayJobCreationResult

    data object TraceNotFound : ReplayJobCreationResult

    data object ApplicationArtifactNotFound : ReplayJobCreationResult

    data object PackageMismatch : ReplayJobCreationResult
}

internal class CreateReplayJob(
    private val traceCatalog: TraceCatalog,
    private val applicationArtifactCatalog: ApplicationArtifactCatalog,
    private val replayJobStore: ReplayJobStore,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    operator fun invoke(request: CreateReplayJobRequest): ReplayJobCreationResult {
        val trace = traceCatalog.find(request.projectId, request.traceId)
            ?: return ReplayJobCreationResult.TraceNotFound
        val artifact = applicationArtifactCatalog.findArtifact(request.projectId, request.applicationArtifactId)
            ?: return ReplayJobCreationResult.ApplicationArtifactNotFound
        if (artifact.packageName != trace.packageName) return ReplayJobCreationResult.PackageMismatch

        val job =
            ReplayJob(
                id = newId(),
                projectId = request.projectId,
                traceId = request.traceId,
                applicationArtifactId = artifact.id,
                packageName = trace.packageName,
                repetitions = request.repetitions,
                attemptTimeout = request.attemptTimeout,
                state = ReplayJobState.QUEUED,
                createdAt = clock.instant(),
            )
        return ReplayJobCreationResult.Created(replayJobStore.create(request, job))
    }
}
