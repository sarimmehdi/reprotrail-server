package dev.reprotrail.server.replay

import dev.reprotrail.server.access.TraceArtifactCatalog
import dev.reprotrail.server.access.TraceArtifactReader
import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.access.TraceCatalog
import dev.reprotrail.server.access.TraceMetadata
import dev.reprotrail.server.access.TracePage
import dev.reprotrail.server.access.TracePageCursor
import dev.reprotrail.server.security.DeveloperIdentity
import dev.reprotrail.server.security.WorkerIdentity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ReplayControllerTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val projectId = UUID.randomUUID()
    private val traceId = UUID.randomUUID()
    private val artifactId = UUID.randomUUID()
    private val developerId = UUID.randomUUID()
    private val workerId = UUID.randomUUID()
    private val jobId = UUID.randomUUID()
    private val leaseId = UUID.randomUUID()
    private val store = FakeReplayStore(replayLease())
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val traceCatalog = ControllerTraceCatalog(trace())
    private val applicationCatalog =
        ApplicationArtifactCatalog { _, _ ->
            ApplicationArtifact(artifactId, "dev.reprotrail.fixture", "applications/app.apk")
        }
    private val create = CreateReplayJob(traceCatalog, applicationCatalog, store, clock) { jobId }
    private val developerController = DeveloperReplayController(create, store)
    private val workerController =
        WorkerReplayController(
            LeaseNextReplayJob(store, clock, Duration.ofMinutes(2)) { leaseId },
            ManageReplayLease(store, clock, Duration.ofMinutes(2)) { _, _, _ -> true },
            DownloadReplayInput(
                store,
                TraceArtifactCatalog { _, _ -> TraceArtifactReference("traces/trace.json") },
                applicationCatalog,
                TraceArtifactReader { reference -> reference.objectKey.encodeToByteArray() },
                clock,
            ),
            UploadReplayArtifact(store, InMemoryReplayContentStore(), clock, 1024),
        )
    private val mockMvc = MockMvcBuilders.standaloneSetup(developerController, workerController).build()

    @Test
    fun `developer creates a bounded replay job`() {
        mockMvc.post("/v1/projects/$projectId/traces/$traceId/replay-jobs") {
            with { request -> request.apply { userPrincipal = developerAuthentication() } }
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {"applicationArtifactId":"$artifactId","repetitions":3,"attemptTimeoutSeconds":600}
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(jobId.toString()) }
            jsonPath("$.state") { value("queued") }
        }
    }

    @Test
    fun `worker leases and downloads inputs only through its active lease`() {
        mockMvc.post("/internal/v1/projects/$projectId/replay-jobs/lease") {
            with { request -> request.apply { userPrincipal = workerAuthentication() } }
        }.andExpect {
            status { isOk() }
            jsonPath("$.jobId") { value(jobId.toString()) }
            jsonPath("$.leaseId") { value(leaseId.toString()) }
        }

        mockMvc.get("/internal/v1/projects/$projectId/replay-jobs/$jobId/leases/$leaseId/trace") {
            with { request -> request.apply { userPrincipal = workerAuthentication() } }
        }.andExpect {
            status { isOk() }
            content { bytes("traces/trace.json".encodeToByteArray()) }
        }
    }

    @Test
    fun `worker completion rejects a stale lease`() {
        store.active = null

        mockMvc.post("/internal/v1/projects/$projectId/replay-jobs/$jobId/leases/$leaseId/complete") {
            with { request -> request.apply { userPrincipal = workerAuthentication() } }
            contentType = MediaType.APPLICATION_JSON
            content = """{"passedRepetitions":3,"failedRepetitions":0,"artifacts":[]}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("lease_not_active") }
        }
    }

    private fun developerAuthentication() =
        UsernamePasswordAuthenticationToken.authenticated(DeveloperIdentity(projectId, developerId), null, emptyList())

    private fun workerAuthentication() =
        UsernamePasswordAuthenticationToken.authenticated(WorkerIdentity(projectId, workerId), null, emptyList())

    private fun trace() =
        TraceMetadata(
            traceId,
            "1.0.0-alpha.1",
            now.minusSeconds(10),
            now.minusSeconds(5),
            "dev.reprotrail.fixture",
            "internal",
            1,
            now.minusSeconds(4),
        )

    private fun replayLease() =
        WorkerReplayLease(
            leaseId,
            jobId,
            projectId,
            traceId,
            artifactId,
            "dev.reprotrail.fixture",
            3,
            Duration.ofMinutes(10),
            now.plusSeconds(120),
        )
}

private class ControllerTraceCatalog(var trace: TraceMetadata?) : TraceCatalog {
    override fun search(
        projectId: UUID,
        criteria: dev.reprotrail.server.access.TraceSearchCriteria,
        cursor: TracePageCursor?,
        limit: Int,
    ): TracePage = error("not used")

    override fun find(projectId: UUID, sessionId: UUID): TraceMetadata? = trace
}

private class FakeReplayStore(var active: WorkerReplayLease?) : ReplayJobStore, ReplayJobReader, ReplayLeaseStore {
    private var job: ReplayJob? = null

    override fun create(request: CreateReplayJobRequest, job: ReplayJob): ReplayJob {
        this.job = job
        return job
    }

    override fun findJob(projectId: UUID, jobId: UUID): ReplayJob? = job

    override fun lease(request: LeaseRequest): WorkerReplayLease? = active

    override fun heartbeat(request: LeaseHeartbeat): Boolean = active != null

    override fun findActive(
        projectId: UUID,
        workerCredentialId: UUID,
        jobId: UUID,
        leaseId: UUID,
        now: Instant,
    ): WorkerReplayLease? = active

    override fun complete(request: CompleteLeaseRequest): Boolean = active != null

    override fun fail(request: FailLeaseRequest): Boolean = active != null
}

private class InMemoryReplayContentStore : ReplayArtifactContentStore {
    override fun putIfAbsent(write: ReplayArtifactContentWrite) = ReplayArtifactContentWriteResult.STORED
}
