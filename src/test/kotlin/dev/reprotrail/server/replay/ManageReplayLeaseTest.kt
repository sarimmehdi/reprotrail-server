package dev.reprotrail.server.replay

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManageReplayLeaseTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val projectId = UUID.randomUUID()
    private val workerId = UUID.randomUUID()
    private val leaseId = UUID.randomUUID()
    private val store = RecordingLeaseStore()
    private val lease =
        LeaseNextReplayJob(
            store,
            Clock.fixed(now, ZoneOffset.UTC),
            Duration.ofMinutes(2),
            { leaseId },
        )

    @Test
    fun `leases one job with bounded ownership`() {
        store.next = replayLease()

        val result = lease(projectId, workerId)

        assertEquals(store.next, result)
        assertEquals(LeaseRequest(projectId, workerId, leaseId, now, now.plusSeconds(120)), store.lastLease)
    }

    @Test
    fun `rejects an unsafe lease duration`() {
        assertFailsWith<IllegalArgumentException> {
            LeaseNextReplayJob(store, Clock.systemUTC(), Duration.ofMinutes(6))
        }
    }

    @Test
    fun `heartbeat extends only the current worker lease`() {
        val manage = ManageReplayLease(store, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(2)) { _, _, _ -> true }
        store.heartbeatResult = true

        assertTrue(manage.heartbeat(projectId, workerId, leaseId))
        assertEquals(LeaseHeartbeat(projectId, workerId, leaseId, now, now.plusSeconds(120)), store.lastHeartbeat)
    }

    @Test
    fun `completion requires counts to match the leased repetitions`() {
        val manage = ManageReplayLease(store, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(2)) { _, _, _ -> true }
        val lease = replayLease()
        store.active = lease

        assertFailsWith<IllegalArgumentException> {
            manage.complete(projectId, workerId, lease.jobId, lease.leaseId, ReplayCompletion(2, 0, emptyList()))
        }
    }

    private fun replayLease() =
        WorkerReplayLease(
            leaseId = leaseId,
            jobId = UUID.randomUUID(),
            projectId = projectId,
            traceId = UUID.randomUUID(),
            applicationArtifactId = UUID.randomUUID(),
            packageName = "dev.reprotrail.fixture",
            repetitions = 3,
            attemptTimeout = Duration.ofMinutes(10),
            expiresAt = now.plusSeconds(120),
        )
}

private class RecordingLeaseStore : ReplayLeaseStore {
    var next: WorkerReplayLease? = null
    var lastLease: LeaseRequest? = null
    var lastHeartbeat: LeaseHeartbeat? = null
    var heartbeatResult = false
    var active: WorkerReplayLease? = null

    override fun lease(request: LeaseRequest): WorkerReplayLease? {
        lastLease = request
        return next
    }

    override fun heartbeat(request: LeaseHeartbeat): Boolean {
        lastHeartbeat = request
        return heartbeatResult
    }

    override fun findActive(
        projectId: UUID,
        workerCredentialId: UUID,
        jobId: UUID,
        leaseId: UUID,
        now: Instant,
    ): WorkerReplayLease? = active

    override fun complete(request: CompleteLeaseRequest): Boolean = true

    override fun fail(request: FailLeaseRequest): Boolean = true
}
