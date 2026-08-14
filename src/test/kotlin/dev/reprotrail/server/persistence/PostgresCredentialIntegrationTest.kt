package dev.reprotrail.server.persistence

import dev.reprotrail.server.access.TraceCatalog
import dev.reprotrail.server.access.TraceArtifactCatalog
import dev.reprotrail.server.access.TraceArtifactDeleter
import dev.reprotrail.server.access.TraceArtifactReader
import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.access.TraceAuditAction
import dev.reprotrail.server.access.TraceAuditEvent
import dev.reprotrail.server.access.TraceAuditLog
import dev.reprotrail.server.access.TraceDeletionCatalog
import dev.reprotrail.server.contract.ValidatedTraceMetadata
import dev.reprotrail.server.ingest.StoredTrace
import dev.reprotrail.server.ingest.TraceRepository
import dev.reprotrail.server.security.HmacTokenDigester
import dev.reprotrail.server.security.AdminCredentialLookup
import dev.reprotrail.server.security.DeveloperCredentialLookup
import dev.reprotrail.server.security.IngestCredentialLookup
import dev.reprotrail.server.security.SecureIngestAuthorizer
import dev.reprotrail.server.retention.TraceRetentionCatalog
import dev.reprotrail.server.reconciliation.ReconciliationState
import dev.reprotrail.server.reconciliation.TraceArtifactInspection
import dev.reprotrail.server.reconciliation.TraceArtifactInspector
import dev.reprotrail.server.reconciliation.TraceReconciliationCatalog
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
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
class PostgresCredentialIntegrationTest {
    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var lookup: IngestCredentialLookup

    @Autowired
    private lateinit var developerLookup: DeveloperCredentialLookup

    @Autowired
    private lateinit var adminLookup: AdminCredentialLookup

    @Autowired
    private lateinit var digester: HmacTokenDigester

    @Autowired
    private lateinit var authorizer: SecureIngestAuthorizer

    @Autowired
    private lateinit var traceRepository: TraceRepository

    @Autowired
    private lateinit var traceCatalog: TraceCatalog

    @Autowired
    private lateinit var traceArtifactCatalog: TraceArtifactCatalog

    @Autowired
    private lateinit var traceAuditLog: TraceAuditLog

    @Autowired
    private lateinit var traceDeletionCatalog: TraceDeletionCatalog

    @Autowired
    private lateinit var traceRetentionCatalog: TraceRetentionCatalog

    @Autowired
    private lateinit var traceReconciliationCatalog: TraceReconciliationCatalog

    @Autowired
    private lateinit var contentStore: InMemoryTraceContentStore

    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val credentialId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f401")
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @BeforeEach
    fun resetDatabase() {
        jdbc.sql("delete from audit_events").update()
        jdbc.sql("delete from traces").update()
        jdbc.sql("delete from developer_credentials").update()
        jdbc.sql("delete from admin_credentials").update()
        jdbc.sql("delete from ingest_credentials").update()
        jdbc.sql("delete from projects").update()
        contentStore.clear()
        jdbc.sql("insert into projects (id, name) values (:id, :name)")
            .param("id", projectId)
            .param("name", "Integration test")
            .update()
    }

    @Test
    fun `Flyway creates the alpha metadata schema`() {
        val tables =
            jdbc.sql(
                """
                select table_name
                from information_schema.tables
                where table_schema = 'public'
                order by table_name
                """.trimIndent(),
            ).query(String::class.java).list()

        assertTrue(
            tables.containsAll(
                listOf(
                    "admin_credentials",
                    "audit_events",
                    "developer_credentials",
                    "ingest_credentials",
                    "projects",
                    "traces",
                ),
            ),
        )
    }

    @Test
    fun `Flyway creates tenant scoped indexes for trace search filters`() {
        val indexes =
            jdbc.sql(
                """
                select indexname
                from pg_indexes
                where schemaname = 'public' and tablename = 'traces'
                """.trimIndent(),
            ).query(String::class.java).list()

        assertTrue(indexes.contains("traces_project_available_started_idx"))
        assertTrue(indexes.contains("traces_project_available_package_started_idx"))
    }

    @Test
    fun `credential lookup is project scoped and preserves lifecycle fields`() {
        val digest = digester.digest(secret)
        insertCredential(digest, expiresAt = Instant.parse("2030-01-01T00:00:00Z"))

        val stored = lookup.find(projectId, credentialId)

        assertNotNull(stored)
        assertArrayEquals(digest, stored?.tokenDigest)
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), stored?.expiresAt)
        assertEquals(null, stored?.revokedAt)
        assertEquals(
            null,
            lookup.find(UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f499"), credentialId),
        )
    }

    @Test
    fun `stored HMAC credential authorizes only the matching secret`() {
        insertCredential(digester.digest(secret), expiresAt = Instant.parse("2030-01-01T00:00:00Z"))

        assertTrue(authorizer.isAuthorized(projectId, "rt_ingest_$credentialId.$secret"))
        assertFalse(authorizer.isAuthorized(projectId, "rt_ingest_$credentialId.${secret.reversed()}"))
    }

    @Test
    fun `developer credential lookup is separately project scoped`() {
        val digest = digester.digest(secret)
        jdbc.sql(
            """
            insert into developer_credentials (id, project_id, token_digest, expires_at)
            values (:id, :projectId, :tokenDigest, :expiresAt)
            """.trimIndent(),
        ).param("id", credentialId)
            .param("projectId", projectId)
            .param("tokenDigest", digest)
            .param("expiresAt", Instant.parse("2030-01-01T00:00:00Z").atOffset(ZoneOffset.UTC))
            .update()

        val stored = developerLookup.find(projectId, credentialId)

        assertArrayEquals(digest, stored?.tokenDigest)
        assertEquals(
            null,
            developerLookup.find(UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f499"), credentialId),
        )
    }

    @Test
    fun `admin credential lookup is separately project scoped`() {
        val digest = digester.digest(secret)
        jdbc.sql(
            """
            insert into admin_credentials (id, project_id, token_digest, expires_at)
            values (:id, :projectId, :tokenDigest, :expiresAt)
            """.trimIndent(),
        ).param("id", credentialId)
            .param("projectId", projectId)
            .param("tokenDigest", digest)
            .param("expiresAt", Instant.parse("2030-01-01T00:00:00Z").atOffset(ZoneOffset.UTC))
            .update()

        val stored = adminLookup.find(projectId, credentialId)

        assertArrayEquals(digest, stored?.tokenDigest)
        assertEquals(null, adminLookup.find(UUID.randomUUID(), credentialId))
    }

    @Test
    fun `trace catalog uses stable tenant scoped keyset pagination and hides unavailable rows`() {
        val newestId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f450")
        val oldestId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f440")
        val pendingId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f460")
        insertTrace(oldestId, "2026-08-11T12:00:00Z", "available")
        insertTrace(newestId, "2026-08-11T12:01:00Z", "available")
        insertTrace(pendingId, "2026-08-11T12:02:00Z", "pending")

        val firstPage = traceCatalog.list(projectId, null, 1)
        val secondPage = traceCatalog.list(projectId, checkNotNull(firstPage.nextCursor), 1)

        assertEquals(listOf(newestId), firstPage.items.map { it.sessionId })
        assertEquals(listOf(oldestId), secondPage.items.map { it.sessionId })
        assertEquals(null, secondPage.nextCursor)
        assertEquals(null, traceCatalog.find(projectId, pendingId))
        assertEquals(
            null,
            traceCatalog.find(UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f499"), newestId),
        )
    }

    @Test
    fun `trace search combines textual metadata time and capture filters within the tenant`() {
        val matchingId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f473")
        val otherPackageId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f474")
        val otherProjectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f499")
        insertTrace(matchingId, "2026-08-11T12:00:00Z", "available", "dev.reprotrail.checkout")
        insertTrace(otherPackageId, "2026-08-11T12:01:00Z", "available", "dev.reprotrail.profile")
        jdbc.sql("insert into projects (id, name) values (:id, :name)")
            .param("id", otherProjectId)
            .param("name", "Other tenant")
            .update()
        insertTrace(
            matchingId,
            "2026-08-11T12:02:00Z",
            "available",
            "dev.reprotrail.checkout",
            project = otherProjectId,
        )

        val page =
            traceCatalog.search(
                projectId = projectId,
                criteria =
                    dev.reprotrail.server.access.TraceSearchCriteria(
                        query = "CHECKOUT",
                        packageName = "dev.reprotrail.checkout",
                        captureMode = "internal",
                        startedAfter = Instant.parse("2026-08-11T11:59:00Z"),
                        startedBefore = Instant.parse("2026-08-11T12:01:00Z"),
                    ),
                cursor = null,
                limit = 10,
            )

        assertEquals(listOf(matchingId), page.items.map { it.sessionId })
    }

    @Test
    fun `artifact lookup and audit persistence retain tenant and actor identity`() {
        insertTrace(traceSessionId, "2026-08-11T12:00:00Z", "available")
        traceAuditLog.append(
            TraceAuditEvent(
                projectId = projectId,
                traceId = traceSessionId,
                actorCredentialId = credentialId,
                action = TraceAuditAction.Downloaded,
                occurredAt = Instant.parse("2026-08-11T12:01:00Z"),
            ),
        )

        val reference = traceArtifactCatalog.findAvailable(projectId, traceSessionId)
        val auditAction =
            jdbc.sql(
                """
                select action
                from audit_events
                where project_id = :projectId and trace_id = :traceId and actor_credential_id = :actorId
                """.trimIndent(),
            ).param("projectId", projectId)
                .param("traceId", traceSessionId)
                .param("actorId", credentialId)
                .query(String::class.java)
                .single()

        assertEquals("projects/$projectId/traces/$traceSessionId.json", reference?.objectKey)
        assertEquals("downloaded", auditAction)
    }

    @Test
    fun `trace deletion reservation completes metadata and tombstone atomically and retries failure`() {
        insertTrace(traceSessionId, "2026-08-11T12:00:00Z", "available")

        val reference = traceDeletionCatalog.reserve(projectId, traceSessionId)
        traceDeletionCatalog.complete(
            TraceAuditEvent(
                projectId = projectId,
                traceId = traceSessionId,
                actorCredentialId = credentialId,
                action = TraceAuditAction.Deleted,
                occurredAt = Instant.parse("2026-08-11T12:01:00Z"),
            ),
        )

        assertEquals("projects/$projectId/traces/$traceSessionId.json", reference?.objectKey)
        assertEquals(0, traceRowCount(traceSessionId))
        assertEquals(
            "deleted",
            jdbc.sql("select action from audit_events where project_id = :projectId and trace_id = :traceId")
                .param("projectId", projectId)
                .param("traceId", traceSessionId)
                .query(String::class.java)
                .single(),
        )

        insertTrace(traceSessionId, "2026-08-11T12:02:00Z", "available")
        traceDeletionCatalog.reserve(projectId, traceSessionId)
        traceDeletionCatalog.markFailed(projectId, traceSessionId)

        assertEquals("delete_failed", traceStorageState(traceSessionId))
        assertNotNull(traceDeletionCatalog.reserve(projectId, traceSessionId))
    }

    @Test
    fun `retention catalog returns only bounded available traces older than cutoff`() {
        val oldId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f470")
        val newId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f471")
        val pendingId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f472")
        insertTrace(oldId, "2026-06-01T00:00:00Z", "available")
        insertTrace(newId, "2026-08-10T00:00:00Z", "available")
        insertTrace(pendingId, "2026-05-01T00:00:00Z", "pending")

        val expired = traceRetentionCatalog.findExpired(Instant.parse("2026-07-01T00:00:00Z"), 1)

        assertEquals(listOf(oldId), expired.map { it.sessionId })
    }

    @Test
    fun `reconciliation catalog finds stale transitions and applies state specific repairs`() {
        val pendingId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f480")
        val deletingId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f481")
        insertTrace(pendingId, "2026-08-01T00:00:00Z", "pending")
        insertTrace(deletingId, "2026-08-01T00:01:00Z", "deleting")

        val candidates =
            traceReconciliationCatalog.findStale(Instant.parse("2026-08-02T00:00:00Z"), 10)
        traceReconciliationCatalog.markAvailable(projectId, pendingId)
        traceReconciliationCatalog.markFailed(projectId, deletingId)

        assertEquals(
            setOf(pendingId to ReconciliationState.Pending, deletingId to ReconciliationState.Deleting),
            candidates.map { it.sessionId to it.state }.toSet(),
        )
        assertEquals("available", traceStorageState(pendingId))
        assertEquals("delete_failed", traceStorageState(deletingId))
    }

    @Test
    fun `trace idempotency is atomic under concurrent retries`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit<dev.reprotrail.server.ingest.TraceCreateResult> {
                    start.await()
                    traceRepository.create(storedTrace())
                }
            }
            start.countDown()

            val outcomes = futures.map { it.get() }.toSet()
            val rowCount =
                jdbc.sql("select count(*) from traces where project_id = :projectId")
                    .param("projectId", projectId)
                    .query(Int::class.java)
                    .single()

            assertEquals(
                setOf(
                    dev.reprotrail.server.ingest.TraceCreateResult.Created,
                    dev.reprotrail.server.ingest.TraceCreateResult.AlreadyExists,
                ),
                outcomes,
            )
            assertEquals(1, rowCount)
            assertEquals(1, contentStore.size())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `reusing an idempotency key for different bytes conflicts`() {
        assertEquals(
            dev.reprotrail.server.ingest.TraceCreateResult.Created,
            traceRepository.create(storedTrace()),
        )

        val changed = storedTrace().copy(contentSha256 = ByteArray(32) { 9 }, content = "changed".encodeToByteArray())

        assertEquals(
            dev.reprotrail.server.ingest.TraceCreateResult.Conflict,
            traceRepository.create(changed),
        )
        assertEquals(1, contentStore.size())
    }

    private fun insertCredential(digest: ByteArray, expiresAt: Instant?) {
        jdbc.sql(
            """
            insert into ingest_credentials (id, project_id, token_digest, expires_at)
            values (:id, :projectId, :tokenDigest, :expiresAt)
            """.trimIndent(),
        ).param("id", credentialId)
            .param("projectId", projectId)
            .param("tokenDigest", digest)
            .param("expiresAt", expiresAt?.atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun insertTrace(
        sessionId: UUID,
        createdAt: String,
        storageState: String,
        packageName: String = "dev.reprotrail.fixture",
        project: UUID = projectId,
    ) {
        jdbc.sql(
            """
            insert into traces (
                project_id, session_id, idempotency_key, content_sha256, object_key,
                schema_version, started_at, package_name, capture_mode, action_count,
                storage_state, reservation_id, created_at, updated_at
            ) values (
                :projectId, :sessionId, :sessionId, :digest, :objectKey,
                '1.0.0-alpha.1', :createdAt, :packageName, 'internal', 1,
                :storageState, :reservationId, :createdAt, :createdAt
            )
            """.trimIndent(),
        ).param("projectId", project)
            .param("sessionId", sessionId)
            .param("digest", ByteArray(32) { it.toByte() })
            .param("objectKey", "projects/$projectId/traces/$sessionId.json")
            .param("createdAt", Instant.parse(createdAt).atOffset(ZoneOffset.UTC))
            .param("packageName", packageName)
            .param("storageState", storageState)
            .param("reservationId", UUID.randomUUID())
            .update()
    }

    private fun traceRowCount(sessionId: UUID): Int =
        jdbc.sql("select count(*) from traces where project_id = :projectId and session_id = :sessionId")
            .param("projectId", projectId)
            .param("sessionId", sessionId)
            .query(Int::class.java)
            .single()

    private fun traceStorageState(sessionId: UUID): String =
        jdbc.sql("select storage_state from traces where project_id = :projectId and session_id = :sessionId")
            .param("projectId", projectId)
            .param("sessionId", sessionId)
            .query(String::class.java)
            .single()

    private fun storedTrace() =
        StoredTrace(
            projectId = projectId,
            idempotencyKey = traceSessionId,
            metadata =
                ValidatedTraceMetadata(
                    schemaVersion = "1.0.0-alpha.1",
                    sessionId = traceSessionId,
                    startedAt = Instant.parse("2026-08-11T12:00:00Z"),
                    endedAt = null,
                    packageName = "dev.reprotrail.fixture",
                    captureMode = "internal",
                    actionCount = 1,
                ),
            content = "{\"trace\":true}".encodeToByteArray(),
            contentSha256 = ByteArray(32) { it.toByte() },
        )

    @TestConfiguration(proxyBeanMethods = false)
    class TestAdapters {
        @Bean
        internal fun traceContentStore(): InMemoryTraceContentStore = InMemoryTraceContentStore()
    }

    internal class InMemoryTraceContentStore :
        TraceContentStore,
        TraceArtifactReader,
        TraceArtifactDeleter,
        TraceArtifactInspector,
        dev.reprotrail.server.replay.ReplayArtifactContentStore {
        private val content = ConcurrentHashMap<String, ByteArray>()

        override fun putIfAbsent(write: TraceContentWrite): TraceContentWriteResult {
            val existing = content.putIfAbsent(write.objectKey, write.content.copyOf())
                ?: return TraceContentWriteResult.Stored
            return if (existing.contentEquals(write.content)) {
                TraceContentWriteResult.AlreadyExists
            } else {
                TraceContentWriteResult.Conflict
            }
        }

        override fun putIfAbsent(
            write: dev.reprotrail.server.replay.ReplayArtifactContentWrite,
        ): dev.reprotrail.server.replay.ReplayArtifactContentWriteResult =
            when (putIfAbsent(TraceContentWrite(write.objectKey, write.content, write.contentSha256, write.contentType))) {
                TraceContentWriteResult.Stored -> dev.reprotrail.server.replay.ReplayArtifactContentWriteResult.STORED
                TraceContentWriteResult.AlreadyExists ->
                    dev.reprotrail.server.replay.ReplayArtifactContentWriteResult.ALREADY_EXISTS
                TraceContentWriteResult.Conflict -> dev.reprotrail.server.replay.ReplayArtifactContentWriteResult.CONFLICT
            }

        override fun read(reference: TraceArtifactReference): ByteArray? = content[reference.objectKey]?.copyOf()

        override fun delete(reference: TraceArtifactReference) {
            content.remove(reference.objectKey)
        }

        override fun inspect(
            reference: TraceArtifactReference,
            expectedSha256: ByteArray,
        ): TraceArtifactInspection {
            val stored = content[reference.objectKey] ?: return TraceArtifactInspection.Missing
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(stored)
            return if (actual.contentEquals(expectedSha256)) {
                TraceArtifactInspection.Matching
            } else {
                TraceArtifactInspection.Conflict
            }
        }

        fun clear() = content.clear()

        fun size(): Int = content.size
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        val traceSessionId: UUID = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")
    }
}
