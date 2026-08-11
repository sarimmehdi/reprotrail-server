package dev.reprotrail.server.storage

import dev.reprotrail.server.access.TraceArtifactReader
import dev.reprotrail.server.access.TraceArtifactReference
import dev.reprotrail.server.access.TraceArtifactDeleter
import dev.reprotrail.server.persistence.TraceContentStore
import dev.reprotrail.server.persistence.TraceContentWrite
import dev.reprotrail.server.persistence.TraceContentWriteResult
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

internal class S3TraceContentStore(
    private val client: S3Client,
    private val bucket: String,
) : TraceContentStore, TraceArtifactReader, TraceArtifactDeleter {
    override fun putIfAbsent(write: TraceContentWrite): TraceContentWriteResult {
        var lastConflict: S3Exception? = null
        repeat(MAX_CONDITIONAL_ATTEMPTS) { attempt ->
            try {
                client.putObject(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(write.objectKey)
                        .contentType("application/vnd.reprotrail.trace+json")
                        .contentLength(write.content.size.toLong())
                        .metadata(mapOf(CONTENT_SHA256_METADATA to write.contentSha256.toHex()))
                        .ifNoneMatch("*")
                        .build(),
                    RequestBody.fromBytes(write.content),
                )
                return TraceContentWriteResult.Stored
            } catch (failure: S3Exception) {
                when (failure.statusCode()) {
                    PRECONDITION_FAILED -> return inspectExisting(write)
                    CONDITIONAL_CONFLICT -> {
                        lastConflict = failure
                        if (attempt == MAX_CONDITIONAL_ATTEMPTS - 1) {
                            return inspectExistingOrThrow(write, failure)
                        }
                    }
                    else -> throw failure
                }
            }
        }
        throw checkNotNull(lastConflict)
    }

    override fun read(reference: TraceArtifactReference): ByteArray? =
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(reference.objectKey).build(),
            ).asByteArray()
        } catch (failure: S3Exception) {
            if (failure.statusCode() == NOT_FOUND) null else throw failure
        }

    override fun delete(reference: TraceArtifactReference) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(reference.objectKey).build())
    }

    private fun inspectExistingOrThrow(
        write: TraceContentWrite,
        conflict: S3Exception,
    ): TraceContentWriteResult =
        try {
            inspectExisting(write)
        } catch (_: S3Exception) {
            throw conflict
        }

    private fun inspectExisting(write: TraceContentWrite): TraceContentWriteResult {
        val existing =
            client.headObject(
                HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(write.objectKey)
                    .build(),
            )
        val sameDigest = existing.metadata()[CONTENT_SHA256_METADATA] == write.contentSha256.toHex()
        val sameLength = existing.contentLength() == write.content.size.toLong()
        return if (sameDigest && sameLength) {
            TraceContentWriteResult.AlreadyExists
        } else {
            TraceContentWriteResult.Conflict
        }
    }

    private companion object {
        const val CONTENT_SHA256_METADATA = "reprotrail-sha256"
        const val PRECONDITION_FAILED = 412
        const val CONDITIONAL_CONFLICT = 409
        const val NOT_FOUND = 404
        const val MAX_CONDITIONAL_ATTEMPTS = 3
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
