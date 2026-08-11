package dev.reprotrail.server.replay

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class UploadReplayArtifactTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val projectId = UUID.randomUUID()
    private val workerId = UUID.randomUUID()
    private val jobId = UUID.randomUUID()
    private val leaseId = UUID.randomUUID()
    private val leases = ArtifactLeaseStore(activeLease())
    private val contents = RecordingContentStore()
    private val upload = UploadReplayArtifact(leases, contents, Clock.fixed(now, ZoneOffset.UTC), 1024)

    @Test
    fun `stores server-digested artifact under a deterministic lease-bound key`() {
        val result =
            upload(
                projectId,
                workerId,
                jobId,
                leaseId,
                ReplayArtifactKind.JUNIT_REPORT,
                "report.xml",
                "report".encodeToByteArray(),
            ) as ReplayArtifactUploadResult.Stored

        assertEquals("projects/$projectId/replays/$jobId/report.xml", contents.write?.objectKey)
        assertEquals("application/octet-stream", contents.write?.contentType)
        assertContentEquals("report".encodeToByteArray(), contents.write?.content)
        assertEquals(contents.write?.contentSha256?.toHex(), result.receipt.sha256)
    }

    @Test
    fun `rejects upload after lease expiry before touching storage`() {
        leases.active = null

        val result =
            upload(projectId, workerId, jobId, leaseId, ReplayArtifactKind.DEVICE_LOG, "log.txt", byteArrayOf(1))

        assertEquals(ReplayArtifactUploadResult.NotActive, result)
        assertEquals(null, contents.write)
    }

    private fun activeLease() =
        WorkerReplayLease(
            leaseId,
            jobId,
            projectId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "dev.reprotrail.fixture",
            1,
            Duration.ofMinutes(10),
            now.plusSeconds(60),
        )
}

private class RecordingContentStore : ReplayArtifactContentStore {
    var write: ReplayArtifactContentWrite? = null

    override fun putIfAbsent(write: ReplayArtifactContentWrite): ReplayArtifactContentWriteResult {
        this.write = write
        return ReplayArtifactContentWriteResult.STORED
    }
}

private class ArtifactLeaseStore(var active: WorkerReplayLease?) : ReplayLeaseStore {
    override fun lease(request: LeaseRequest): WorkerReplayLease? = null

    override fun heartbeat(request: LeaseHeartbeat): Boolean = false

    override fun findActive(
        projectId: UUID,
        workerCredentialId: UUID,
        jobId: UUID,
        leaseId: UUID,
        now: Instant,
    ): WorkerReplayLease? = active

    override fun complete(request: CompleteLeaseRequest): Boolean = false

    override fun fail(request: FailLeaseRequest): Boolean = false
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
