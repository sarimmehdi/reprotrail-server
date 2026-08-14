package dev.reprotrail.server

import dev.reprotrail.server.access.TraceCatalog
import dev.reprotrail.server.access.TraceArtifactCatalog
import dev.reprotrail.server.access.TraceArtifactReader
import dev.reprotrail.server.access.TraceArtifactDeleter
import dev.reprotrail.server.access.TraceAuditLog
import dev.reprotrail.server.access.TraceDeletionCatalog
import dev.reprotrail.server.access.TraceMetadata
import dev.reprotrail.server.access.TracePage
import dev.reprotrail.server.access.TracePageCursor
import dev.reprotrail.server.ingest.IngestAuthorizer
import dev.reprotrail.server.ingest.StoredTrace
import dev.reprotrail.server.ingest.TraceCreateResult
import dev.reprotrail.server.ingest.TraceIngestor
import dev.reprotrail.server.ingest.TraceRepository
import dev.reprotrail.server.security.DeveloperAuthorizer
import dev.reprotrail.server.security.DeveloperIdentity
import dev.reprotrail.server.security.WorkerAuthorizer
import dev.reprotrail.server.security.WorkerIdentity
import java.time.Clock
import dev.reprotrail.server.retention.TraceRetentionCatalog
import dev.reprotrail.server.reconciliation.TraceArtifactInspection
import dev.reprotrail.server.reconciliation.TraceArtifactInspector
import dev.reprotrail.server.reconciliation.TraceReconciliationCatalog
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    ],
)
@Import(ReproTrailServerApplicationTest.InfrastructureTestConfiguration::class)
@ActiveProfiles("test")
class ReproTrailServerApplicationTest {
    @Autowired
    private lateinit var traceIngestor: TraceIngestor

    @Autowired
    private lateinit var securityFilterChain: SecurityFilterChain

    @Test
    fun `application composes the trace ingestion boundary`() {
        assertNotNull(traceIngestor)
        assertNotNull(securityFilterChain)
    }

    @TestConfiguration
    class InfrastructureTestConfiguration {
        @Bean
        internal fun ingestAuthorizer(): IngestAuthorizer = IngestAuthorizer { _, _ -> true }

        @Bean
        internal fun developerAuthorizer(): DeveloperAuthorizer =
            DeveloperAuthorizer { projectId, _ -> DeveloperIdentity(projectId, java.util.UUID.randomUUID()) }

        @Bean
        internal fun adminAuthorizer(): dev.reprotrail.server.security.AdminAuthorizer =
            dev.reprotrail.server.security.AdminAuthorizer { projectId, _ ->
                dev.reprotrail.server.security.AdminIdentity(projectId, java.util.UUID.randomUUID())
            }

        @Bean
        internal fun workerAuthorizer(): WorkerAuthorizer =
            WorkerAuthorizer { projectId, _ -> WorkerIdentity(projectId, java.util.UUID.randomUUID()) }

        @Bean
        internal fun traceRepository(): TraceRepository =
            object : TraceRepository {
                override fun create(record: StoredTrace): TraceCreateResult = TraceCreateResult.Created
            }

        @Bean
        internal fun traceCatalog(): TraceCatalog =
            object : TraceCatalog {
                override fun search(
                    projectId: java.util.UUID,
                    criteria: dev.reprotrail.server.access.TraceSearchCriteria,
                    cursor: TracePageCursor?,
                    limit: Int,
                ): TracePage = TracePage(emptyList(), null)

                override fun find(projectId: java.util.UUID, sessionId: java.util.UUID): TraceMetadata? = null
            }

        @Bean
        internal fun traceArtifactCatalog(): TraceArtifactCatalog = TraceArtifactCatalog { _, _ -> null }

        @Bean
        internal fun traceArtifactReader(): TraceArtifactReader = TraceArtifactReader { null }

        @Bean
        internal fun traceAuditLog(): TraceAuditLog = TraceAuditLog { }

        @Bean
        internal fun traceArtifactDeleter(): TraceArtifactDeleter = TraceArtifactDeleter { }

        @Bean
        internal fun traceDeletionCatalog(): TraceDeletionCatalog =
            object : TraceDeletionCatalog {
                override fun reserve(projectId: java.util.UUID, sessionId: java.util.UUID) = null

                override fun complete(event: dev.reprotrail.server.access.TraceAuditEvent) = Unit

                override fun markFailed(projectId: java.util.UUID, sessionId: java.util.UUID) = Unit
            }

        @Bean
        internal fun applicationClock(): Clock = Clock.systemUTC()

        @Bean
        internal fun traceRetentionCatalog(): TraceRetentionCatalog = TraceRetentionCatalog { _, _, _ -> emptyList() }

        @Bean
        internal fun retentionPolicyStore(): dev.reprotrail.server.retention.RetentionPolicyStore =
            object : dev.reprotrail.server.retention.RetentionPolicyStore {
                override fun find(projectId: java.util.UUID) = null

                override fun update(update: dev.reprotrail.server.retention.RetentionPolicyUpdate) =
                    dev.reprotrail.server.retention.ProjectRetentionPolicy(
                        update.projectId,
                        update.retainForDays,
                        true,
                        update.updatedAt,
                        update.adminCredentialId,
                    )
            }

        @Bean
        internal fun traceArtifactInspector(): TraceArtifactInspector =
            TraceArtifactInspector { _, _ -> TraceArtifactInspection.Missing }

        @Bean
        internal fun traceReconciliationCatalog(): TraceReconciliationCatalog =
            object : TraceReconciliationCatalog {
                override fun findStale(updatedBefore: java.time.Instant, limit: Int) = emptyList<dev.reprotrail.server.reconciliation.ReconciliationCandidate>()

                override fun markAvailable(projectId: java.util.UUID, sessionId: java.util.UUID) = Unit

                override fun markFailed(projectId: java.util.UUID, sessionId: java.util.UUID) = Unit
            }

        @Bean
        internal fun replayStore(): FakeReplayStore = FakeReplayStore()

        @Bean
        internal fun replayArtifactContentStore(): dev.reprotrail.server.replay.ReplayArtifactContentStore =
            dev.reprotrail.server.replay.ReplayArtifactContentStore {
                dev.reprotrail.server.replay.ReplayArtifactContentWriteResult.STORED
            }
    }

    internal class FakeReplayStore :
        dev.reprotrail.server.replay.ApplicationArtifactCatalog,
        dev.reprotrail.server.replay.ReplayArtifactCatalog,
        dev.reprotrail.server.replay.ReplayJobStore,
        dev.reprotrail.server.replay.ReplayJobReader,
        dev.reprotrail.server.replay.ReplayLeaseStore {
        override fun findArtifact(projectId: java.util.UUID, artifactId: java.util.UUID) = null

        override fun findReplayArtifact(projectId: java.util.UUID, jobId: java.util.UUID, name: String) = null

        override fun create(
            request: dev.reprotrail.server.replay.CreateReplayJobRequest,
            job: dev.reprotrail.server.replay.ReplayJob,
        ) = job

        override fun findJob(projectId: java.util.UUID, jobId: java.util.UUID) = null

        override fun listJobs(projectId: java.util.UUID, traceId: java.util.UUID, limit: Int) =
            emptyList<dev.reprotrail.server.replay.ReplayJob>()

        override fun lease(request: dev.reprotrail.server.replay.LeaseRequest) = null

        override fun heartbeat(request: dev.reprotrail.server.replay.LeaseHeartbeat) = false

        override fun findActive(
            projectId: java.util.UUID,
            workerCredentialId: java.util.UUID,
            jobId: java.util.UUID,
            leaseId: java.util.UUID,
            now: java.time.Instant,
        ) = null

        override fun complete(request: dev.reprotrail.server.replay.CompleteLeaseRequest) = false

        override fun fail(request: dev.reprotrail.server.replay.FailLeaseRequest) = false
    }
}
