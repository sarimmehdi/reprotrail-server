package dev.reprotrail.server.replay

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class WorkerReplayLease(
    val leaseId: UUID,
    val jobId: UUID,
    val projectId: UUID,
    val traceId: UUID,
    val applicationArtifactId: UUID,
    val packageName: String,
    val repetitions: Int,
    val attemptTimeout: Duration,
    val expiresAt: Instant,
)

internal data class LeaseRequest(
    val projectId: UUID,
    val workerCredentialId: UUID,
    val leaseId: UUID,
    val now: Instant,
    val expiresAt: Instant,
)

internal data class LeaseHeartbeat(
    val projectId: UUID,
    val workerCredentialId: UUID,
    val leaseId: UUID,
    val now: Instant,
    val expiresAt: Instant,
)

internal enum class ReplayArtifactKind {
    JUNIT_REPORT,
    MAESTRO_OUTPUT,
    DEVICE_LOG,
}

internal data class ReplayArtifactReceipt(
    val kind: ReplayArtifactKind,
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    init {
        require(name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))) { "Invalid artifact name." }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid artifact digest." }
        require(sizeBytes >= 0) { "Artifact size cannot be negative." }
    }
}

internal data class ReplayCompletion(
    val passedRepetitions: Int,
    val failedRepetitions: Int,
    val artifacts: List<ReplayArtifactReceipt>,
) {
    init {
        require(passedRepetitions >= 0 && failedRepetitions >= 0) { "Replay counts cannot be negative." }
        require(passedRepetitions + failedRepetitions > 0) { "At least one replay repetition is required." }
        require(artifacts.size <= 100) { "A replay attempt cannot publish more than 100 artifacts." }
    }
}

internal enum class WorkerReplayFailureCode {
    INPUT_DOWNLOAD_FAILED,
    ENVIRONMENT_PROVISIONING_FAILED,
    APP_INSTALL_FAILED,
    EXECUTION_FAILED,
    ARTIFACT_UPLOAD_FAILED,
    WORKER_INTERNAL,
}

internal data class WorkerReplayFailure(
    val code: WorkerReplayFailureCode,
    val summary: String,
) {
    init {
        require(summary.isNotBlank() && summary.length <= 240) { "Invalid replay failure summary." }
    }
}

internal data class CompleteLeaseRequest(
    val projectId: UUID,
    val workerCredentialId: UUID,
    val leaseId: UUID,
    val jobId: UUID,
    val completion: ReplayCompletion,
    val now: Instant,
)

internal data class FailLeaseRequest(
    val projectId: UUID,
    val workerCredentialId: UUID,
    val leaseId: UUID,
    val jobId: UUID,
    val failure: WorkerReplayFailure,
    val now: Instant,
)

internal interface ReplayLeaseStore {
    fun lease(request: LeaseRequest): WorkerReplayLease?

    fun heartbeat(request: LeaseHeartbeat): Boolean

    fun findActive(
        projectId: UUID,
        workerCredentialId: UUID,
        jobId: UUID,
        leaseId: UUID,
        now: Instant,
    ): WorkerReplayLease?

    fun complete(request: CompleteLeaseRequest): Boolean

    fun fail(request: FailLeaseRequest): Boolean
}

internal class LeaseNextReplayJob(
    private val store: ReplayLeaseStore,
    private val clock: Clock,
    private val leaseDuration: Duration,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    init {
        require(leaseDuration in MINIMUM_LEASE..MAXIMUM_LEASE) { "Lease duration must be between 10 seconds and 5 minutes." }
    }

    operator fun invoke(projectId: UUID, workerCredentialId: UUID): WorkerReplayLease? {
        val now = clock.instant()
        return store.lease(LeaseRequest(projectId, workerCredentialId, newId(), now, now.plus(leaseDuration)))
    }
}

internal class ManageReplayLease(
    private val store: ReplayLeaseStore,
    private val clock: Clock,
    private val leaseDuration: Duration,
) {
    init {
        require(leaseDuration in MINIMUM_LEASE..MAXIMUM_LEASE) { "Lease duration must be between 10 seconds and 5 minutes." }
    }

    fun heartbeat(projectId: UUID, workerCredentialId: UUID, leaseId: UUID): Boolean {
        val now = clock.instant()
        return store.heartbeat(LeaseHeartbeat(projectId, workerCredentialId, leaseId, now, now.plus(leaseDuration)))
    }

    fun complete(
        projectId: UUID,
        workerCredentialId: UUID,
        jobId: UUID,
        leaseId: UUID,
        completion: ReplayCompletion,
    ): Boolean {
        val lease = store.findActive(projectId, workerCredentialId, jobId, leaseId, clock.instant()) ?: return false
        require(completion.passedRepetitions + completion.failedRepetitions == lease.repetitions) {
            "Replay counts must match the leased repetition count."
        }
        return store.complete(
            CompleteLeaseRequest(projectId, workerCredentialId, lease.leaseId, lease.jobId, completion, clock.instant()),
        )
    }

    fun fail(
        projectId: UUID,
        workerCredentialId: UUID,
        jobId: UUID,
        leaseId: UUID,
        failure: WorkerReplayFailure,
    ): Boolean {
        val now = clock.instant()
        val lease = store.findActive(projectId, workerCredentialId, jobId, leaseId, now) ?: return false
        return store.fail(FailLeaseRequest(projectId, workerCredentialId, lease.leaseId, lease.jobId, failure, now))
    }
}

private val MINIMUM_LEASE: Duration = Duration.ofSeconds(10)
private val MAXIMUM_LEASE: Duration = Duration.ofMinutes(5)
