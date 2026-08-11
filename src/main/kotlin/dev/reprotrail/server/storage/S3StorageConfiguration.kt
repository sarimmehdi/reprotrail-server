package dev.reprotrail.server.storage

import dev.reprotrail.server.access.TraceArtifactReader
import dev.reprotrail.server.persistence.TraceContentStore
import java.net.URI
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

@ConfigurationProperties("reprotrail.storage.s3")
internal data class S3StorageProperties(
    var bucket: String = "",
    var region: String = "us-east-1",
    var endpoint: URI? = null,
    var accessKey: String? = null,
    var secretKey: String? = null,
    var pathStyle: Boolean = false,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3StorageProperties::class)
@ConditionalOnProperty(prefix = "reprotrail.storage.s3", name = ["bucket"])
internal class S3StorageConfiguration {
    @Bean
    fun s3Client(properties: S3StorageProperties): S3Client {
        require(properties.bucket.isNotBlank()) { "The trace storage bucket must not be blank." }
        val builder =
            S3Client.builder()
                .region(Region.of(properties.region))
                .forcePathStyle(properties.pathStyle)
        properties.endpoint?.let(builder::endpointOverride)

        val accessKey = properties.accessKey
        val secretKey = properties.secretKey
        require((accessKey == null) == (secretKey == null)) {
            "S3 access-key and secret-key configuration must be provided together."
        }
        if (accessKey != null && secretKey != null) {
            builder.credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)),
            )
        }
        return builder.build()
    }

    @Bean
    fun traceContentStore(client: S3Client, properties: S3StorageProperties): TraceContentStore =
        S3TraceContentStore(client, properties.bucket)

    @Bean
    fun traceArtifactReader(client: S3Client, properties: S3StorageProperties): TraceArtifactReader =
        S3TraceContentStore(client, properties.bucket)
}
