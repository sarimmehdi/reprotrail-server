package dev.reprotrail.server.persistence

import dev.reprotrail.server.ingest.StoredTrace
import dev.reprotrail.server.ingest.TraceCreateResult
import dev.reprotrail.server.ingest.TraceRepository
import dev.reprotrail.server.security.HmacTokenDigester
import dev.reprotrail.server.security.IngestCredentialLookup
import dev.reprotrail.server.security.SecureIngestAuthorizer
import java.time.Instant
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
    private lateinit var digester: HmacTokenDigester

    @Autowired
    private lateinit var authorizer: SecureIngestAuthorizer

    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val credentialId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f401")
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @BeforeEach
    fun resetDatabase() {
        jdbc.sql("delete from audit_events").update()
        jdbc.sql("delete from traces").update()
        jdbc.sql("delete from ingest_credentials").update()
        jdbc.sql("delete from projects").update()
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

        assertTrue(tables.containsAll(listOf("audit_events", "ingest_credentials", "projects", "traces")))
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

    private fun insertCredential(digest: ByteArray, expiresAt: Instant?) {
        jdbc.sql(
            """
            insert into ingest_credentials (id, project_id, token_digest, expires_at)
            values (:id, :projectId, :tokenDigest, :expiresAt)
            """.trimIndent(),
        ).param("id", credentialId)
            .param("projectId", projectId)
            .param("tokenDigest", digest)
            .param("expiresAt", expiresAt)
            .update()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestAdapters {
        @Bean
        internal fun traceRepository(): TraceRepository =
            object : TraceRepository {
                override fun create(record: StoredTrace): TraceCreateResult = TraceCreateResult.Created
            }
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
