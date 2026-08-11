package dev.reprotrail.server.storage

import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.persistence.TraceContentWrite
import dev.reprotrail.server.persistence.TraceContentWriteResult
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest

@Testcontainers(disabledWithoutDocker = true)
class S3TraceContentStoreIntegrationTest {
    private lateinit var client: S3Client
    private lateinit var store: S3TraceContentStore
    private lateinit var bucket: String

    @BeforeEach
    fun createBucket() {
        client =
            S3Client.builder()
                .endpointOverride(URI.create(minio.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getUserName(), minio.getPassword()),
                    ),
                ).forcePathStyle(true)
                .build()
        bucket = "reprotrail-${UUID.randomUUID()}"
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        store = S3TraceContentStore(client, bucket)
    }

    @Test
    fun `conditional writes preserve the first immutable trace`() {
        val objectKey = "projects/project/traces/session.json"
        val original = "{\"trace\":1}".encodeToByteArray()
        val changed = "{\"trace\":2}".encodeToByteArray()

        assertEquals(TraceContentWriteResult.Stored, store.putIfAbsent(write(objectKey, original)))
        assertEquals(TraceContentWriteResult.AlreadyExists, store.putIfAbsent(write(objectKey, original)))
        assertEquals(TraceContentWriteResult.Conflict, store.putIfAbsent(write(objectKey, changed)))

        val stored =
            client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(objectKey).build(),
                ResponseTransformer.toBytes(),
            ).asByteArray()
        assertArrayEquals(original, stored)
        assertArrayEquals(original, store.read(TraceArtifactReference(objectKey)))
        store.delete(TraceArtifactReference(objectKey))
        store.delete(TraceArtifactReference(objectKey))
        assertEquals(null, store.read(TraceArtifactReference(objectKey)))
        assertEquals(null, store.read(TraceArtifactReference("missing.json")))
    }

    private fun write(objectKey: String, content: ByteArray) =
        TraceContentWrite(
            objectKey = objectKey,
            content = content,
            contentSha256 = MessageDigest.getInstance("SHA-256").digest(content),
        )

    companion object {
        @Container
        @JvmStatic
        val minio = MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")
    }
}
