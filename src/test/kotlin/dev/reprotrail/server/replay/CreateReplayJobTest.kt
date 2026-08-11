package dev.reprotrail.server.replay

import dev.reprotrail.server.access.TraceCatalog
import dev.reprotrail.server.access.TraceMetadata
import dev.reprotrail.server.access.TracePage
import dev.reprotrail.server.access.TracePageCursor
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreateReplayJobTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val projectId = UUID.randomUUID()
    private val traceId = UUID.randomUUID()
    private val artifactId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()
    private val traceCatalog = FakeTraceCatalog(trace())
    private val artifactCatalog = FakeApplicationArtifactCatalog(applicationArtifact())
    private val store = RecordingReplayJobStore()
    private val create =
        CreateReplayJob(
            traceCatalog,
            artifactCatalog,
            store,
            Clock.fixed(now, ZoneOffset.UTC),
            { UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f499") },
        )

    @Test
    fun `creates a queued project-scoped replay job`() {
        val result = create(request())

        val created = result as ReplayJobCreationResult.Created
        assertEquals(ReplayJobState.QUEUED, created.job.state)
        assertEquals(now, created.job.createdAt)
        assertEquals(request().copy(), store.created)
        assertEquals(created.job, store.savedJob)
    }

    @Test
    fun `does not disclose a trace from another project`() {
        traceCatalog.found = null

        assertEquals(ReplayJobCreationResult.TraceNotFound, create(request()))
        assertNull(store.savedJob)
    }

    @Test
    fun `rejects an unknown application artifact`() {
        artifactCatalog.found = null

        assertEquals(ReplayJobCreationResult.ApplicationArtifactNotFound, create(request()))
        assertNull(store.savedJob)
    }

    @Test
    fun `rejects an application artifact for another package`() {
        artifactCatalog.found = applicationArtifact().copy(packageName = "dev.other.app")

        assertEquals(ReplayJobCreationResult.PackageMismatch, create(request()))
        assertNull(store.savedJob)
    }

    private fun request() =
        CreateReplayJobRequest(
            projectId = projectId,
            traceId = traceId,
            applicationArtifactId = artifactId,
            repetitions = 3,
            attemptTimeout = Duration.ofMinutes(10),
            actorCredentialId = actorId,
        )

    private fun trace() =
        TraceMetadata(
            sessionId = traceId,
            schemaVersion = "1.0.0-alpha.1",
            startedAt = now.minusSeconds(10),
            endedAt = now.minusSeconds(5),
            packageName = "dev.reprotrail.fixture",
            captureMode = "internal",
            actionCount = 1,
            createdAt = now.minusSeconds(4),
        )

    private fun applicationArtifact() =
        ApplicationArtifact(artifactId, "dev.reprotrail.fixture", "applications/$projectId/$artifactId.apk")
}

private class FakeTraceCatalog(var found: TraceMetadata?) : TraceCatalog {
    override fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage = error("not used")

    override fun find(projectId: UUID, sessionId: UUID): TraceMetadata? = found
}

private class FakeApplicationArtifactCatalog(var found: ApplicationArtifact?) : ApplicationArtifactCatalog {
    override fun findArtifact(projectId: UUID, artifactId: UUID): ApplicationArtifact? = found
}

private class RecordingReplayJobStore : ReplayJobStore {
    var created: CreateReplayJobRequest? = null
    var savedJob: ReplayJob? = null

    override fun create(request: CreateReplayJobRequest, job: ReplayJob): ReplayJob {
        created = request
        savedJob = job
        return job
    }
}
