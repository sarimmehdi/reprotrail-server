package dev.reprotrail.server.persistence

import dev.reprotrail.server.replay.CompleteLeaseRequest
import dev.reprotrail.server.replay.CreateReplayJobRequest
import dev.reprotrail.server.replay.LeaseRequest
import dev.reprotrail.server.replay.ReplayArtifactKind
import dev.reprotrail.server.replay.ReplayArtifactReceipt
import dev.reprotrail.server.replay.ReplayCompletion
import dev.reprotrail.server.replay.ReplayJob
import dev.reprotrail.server.replay.ReplayJobState
import dev.reprotrail.server.replay.WorkerReplayLease
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "reprotrail.security.token-pepper-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    ],
)
@Import(PostgresCredentialIntegrationTest.TestAdapters::class)
class JdbcReplayRepositoryIntegrationTest {
    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var repository: JdbcReplayRepository

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val projectId = UUID.randomUUID()
    private val traceId = UUID.randomUUID()
    private val artifactId = UUID.randomUUID()
    private val developerId = UUID.randomUUID()
    private val workerId = UUID.randomUUID()
    private val jobId = UUID.randomUUID()

    @BeforeEach
    fun seedReplayInputs() {
        jdbc.sql("delete from replay_artifacts").update()
        jdbc.sql("delete from replay_jobs").update()
        jdbc.sql("delete from application_artifacts").update()
        jdbc.sql("delete from worker_credentials").update()
        jdbc.sql("delete from audit_events").update()
        jdbc.sql("delete from traces").update()
        jdbc.sql("delete from developer_credentials").update()
        jdbc.sql("delete from ingest_credentials").update()
        jdbc.sql("delete from projects").update()
        jdbc.sql("insert into projects (id, name) values (:id, 'Replay test')").param("id", projectId).update()
        jdbc.sql(
            """
            insert into developer_credentials (id, project_id, token_digest)
            values (:id, :projectId, :digest)
            """.trimIndent(),
        ).param("id", developerId).param("projectId", projectId).param("digest", ByteArray(32)).update()
        jdbc.sql(
            """
            insert into worker_credentials (id, project_id, token_digest)
            values (:id, :projectId, :digest)
            """.trimIndent(),
        ).param("id", workerId).param("projectId", projectId).param("digest", ByteArray(32) { 1 }).update()
        jdbc.sql(
            """
            insert into traces (
                project_id, session_id, idempotency_key, content_sha256, object_key,
                schema_version, started_at, package_name, capture_mode, action_count,
                storage_state, reservation_id
            ) values (
                :projectId, :traceId, :traceId, :digest, :objectKey,
                '1.0.0-alpha.1', :startedAt, 'dev.reprotrail.fixture', 'internal', 1,
                'available', :reservationId
            )
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("traceId", traceId)
            .param("digest", ByteArray(32) { 2 })
            .param("objectKey", "projects/$projectId/traces/$traceId.json")
            .param("startedAt", now.minusSeconds(10).atOffset(ZoneOffset.UTC))
            .param("reservationId", UUID.randomUUID())
            .update()
        jdbc.sql(
            """
            insert into application_artifacts (
                project_id, id, package_name, object_key, content_sha256, size_bytes
            ) values (
                :projectId, :id, 'dev.reprotrail.fixture', :objectKey, :digest, 1024
            )
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("id", artifactId)
            .param("objectKey", "projects/$projectId/applications/$artifactId.apk")
            .param("digest", ByteArray(32) { 3 })
            .update()
        createJob()
    }

    @Test
    fun `concurrent workers cannot lease the same queued job`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val leases =
                List(2) {
                    executor.submit<WorkerReplayLease?> {
                        start.await()
                        repository.lease(leaseRequest(UUID.randomUUID(), now))
                    }
                }
            start.countDown()

            assertEquals(1, leases.map { it.get() }.count { it != null })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `expired lease is recovered and stale owner cannot complete it`() {
        val oldLease = assertNotNull(repository.lease(leaseRequest(UUID.randomUUID(), now)))
        val recoveredAt = oldLease.expiresAt.plusSeconds(1)
        val newLease = assertNotNull(repository.lease(leaseRequest(UUID.randomUUID(), recoveredAt)))
        val completion = ReplayCompletion(3, 0, listOf(reportReceipt()))

        assertFalse(
            repository.complete(
                CompleteLeaseRequest(projectId, workerId, oldLease.leaseId, jobId, completion, recoveredAt),
            ),
        )
        assertTrue(
            repository.complete(
                CompleteLeaseRequest(projectId, workerId, newLease.leaseId, jobId, completion, recoveredAt.plusSeconds(1)),
            ),
        )
        assertEquals("succeeded", replayState())
        assertEquals(1, jdbc.sql("select count(*) from replay_artifacts").query(Int::class.java).single())
        assertNull(repository.lease(leaseRequest(UUID.randomUUID(), recoveredAt.plusSeconds(2))))
    }

    @Test
    fun `application artifact lookup remains project scoped`() {
        assertEquals(artifactId, repository.findArtifact(projectId, artifactId)?.id)
        assertNull(repository.findArtifact(UUID.randomUUID(), artifactId))
    }

    private fun createJob() {
        val request =
            CreateReplayJobRequest(projectId, traceId, artifactId, 3, Duration.ofMinutes(10), developerId)
        repository.create(
            request,
            ReplayJob(
                jobId,
                projectId,
                traceId,
                artifactId,
                "dev.reprotrail.fixture",
                3,
                Duration.ofMinutes(10),
                ReplayJobState.QUEUED,
                now,
            ),
        )
    }

    private fun leaseRequest(leaseId: UUID, requestedAt: Instant) =
        LeaseRequest(projectId, workerId, leaseId, requestedAt, requestedAt.plusSeconds(120))

    private fun reportReceipt() =
        ReplayArtifactReceipt(ReplayArtifactKind.JUNIT_REPORT, "report.xml", "a".repeat(64), 42)

    private fun replayState(): String =
        jdbc.sql("select state from replay_jobs where project_id = :projectId and id = :jobId")
            .param("projectId", projectId)
            .param("jobId", jobId)
            .query(String::class.java)
            .single()

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
