package dev.reprotrail.server.ingest

import dev.reprotrail.server.security.HmacTokenDigester
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class TraceIngestionIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var digester: HmacTokenDigester

    @BeforeEach
    fun seedProjectCredential() {
        jdbc.sql("delete from audit_events").update()
        jdbc.sql("delete from traces").update()
        jdbc.sql("delete from ingest_credentials").update()
        jdbc.sql("delete from projects").update()
        jdbc.sql("insert into projects (id, name) values (:id, :name)")
            .param("id", projectId)
            .param("name", "HTTP integration test")
            .update()
        jdbc.sql(
            """
            insert into ingest_credentials (id, project_id, token_digest, expires_at)
            values (:id, :projectId, :digest, :expiresAt)
            """.trimIndent(),
        ).param("id", credentialId)
            .param("projectId", projectId)
            .param("digest", digester.digest(secret))
            .param("expiresAt", Instant.parse("2030-01-01T00:00:00Z").atOffset(ZoneOffset.UTC))
            .update()
    }

    @Test
    fun `unauthorized content is rejected before trace parsing`() {
        mockMvc.post(traceCollectionPath) {
            header("Idempotency-Key", sessionId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = "{not-json"
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("unauthorized") }
        }

        assertEquals(0, traceCount())
    }

    @Test
    fun `oversized authorized content is rejected before trace parsing`() {
        mockMvc.post(traceCollectionPath) {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", sessionId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = ByteArray(MAX_TRACE_BYTES + 1)
        }.andExpect {
            status { isContentTooLarge() }
            jsonPath("$.code") { value("trace_too_large") }
        }

        assertEquals(0, traceCount())
    }

    @Test
    fun `valid trace ingestion is immutable and idempotent end to end`() {
        authorizedIngest(validTrace()).andExpect { status { isCreated() } }
        authorizedIngest(validTrace()).andExpect { status { isOk() } }
        authorizedIngest(validTrace() + "\n").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("idempotency_conflict") }
        }

        val state =
            jdbc.sql(
                """
                select storage_state
                from traces
                where project_id = :projectId and session_id = :sessionId
                """.trimIndent(),
            ).param("projectId", projectId)
                .param("sessionId", sessionId)
                .query(String::class.java)
                .single()
        val storedContent =
            s3Client.getObject(
                GetObjectRequest.builder()
                    .bucket(bucket)
                    .key("projects/$projectId/traces/$sessionId.json")
                    .build(),
                ResponseTransformer.toBytes(),
            ).asByteArray()

        assertEquals("available", state)
        assertEquals(1, traceCount())
        assertArrayEquals(validTrace().encodeToByteArray(), storedContent)
    }

    private fun authorizedIngest(body: String) =
        mockMvc.post(traceCollectionPath) {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", sessionId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun traceCount(): Int =
        jdbc.sql("select count(*) from traces where project_id = :projectId")
            .param("projectId", projectId)
            .query(Int::class.java)
            .single()

    private fun validTrace(): String =
        checkNotNull(javaClass.getResource("/fixtures/v1alpha1/valid/minimal-tap.json")).readText()

    private val token: String
        get() = "rt_ingest_$credentialId.$secret"

    private val traceCollectionPath: String
        get() = "/v1/projects/$projectId/traces"

    companion object {
        private const val MAX_TRACE_BYTES = 1_048_576
        private const val PEPPER_BASE64 = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
        private val projectId: UUID = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
        private val credentialId: UUID = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f401")
        private val sessionId: UUID = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")
        private val secret: String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        private val bucket = "reprotrail-integration-${UUID.randomUUID()}"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @Container
        @JvmStatic
        val minio = MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")

        private lateinit var s3Client: S3Client

        @JvmStatic
        @BeforeAll
        fun createObjectStore() {
            s3Client =
                S3Client.builder()
                    .endpointOverride(URI.create(minio.getS3URL()))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(minio.getUserName(), minio.getPassword()),
                        ),
                    ).forcePathStyle(true)
                    .build()
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        }

        @JvmStatic
        @AfterAll
        fun closeObjectStoreClient() {
            if (::s3Client.isInitialized) s3Client.close()
        }

        @JvmStatic
        @DynamicPropertySource
        fun infrastructureProperties(registry: DynamicPropertyRegistry) {
            registry.add("reprotrail.security.token-pepper-base64") { PEPPER_BASE64 }
            registry.add("reprotrail.storage.s3.bucket") { bucket }
            registry.add("reprotrail.storage.s3.region") { "us-east-1" }
            registry.add("reprotrail.storage.s3.endpoint") { minio.getS3URL() }
            registry.add("reprotrail.storage.s3.access-key") { minio.getUserName() }
            registry.add("reprotrail.storage.s3.secret-key") { minio.getPassword() }
            registry.add("reprotrail.storage.s3.path-style") { true }
        }
    }
}
