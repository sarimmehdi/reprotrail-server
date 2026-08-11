package dev.reprotrail.server.persistence

import dev.reprotrail.server.contract.ValidatedTraceMetadata
import dev.reprotrail.server.ingest.StoredTrace
import dev.reprotrail.server.ingest.TraceCreateResult
import java.io.IOException
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PersistentTraceRepositoryTest {
    private val metadataStore = RecordingTraceMetadataStore()
    private val contentStore = RecordingTraceContentStore()
    private val repository = PersistentTraceRepository(metadataStore, contentStore)

    @Test
    fun `a new reservation stores immutable content and becomes available`() {
        metadataStore.nextReservation = TraceReservation.Created

        val result = repository.create(trace())

        assertEquals(TraceCreateResult.Created, result)
        assertEquals("projects/$projectId/traces/$sessionId.json", contentStore.lastWrite?.objectKey)
        assertEquals(projectId to sessionId, metadataStore.available)
    }

    @Test
    fun `an available matching reservation returns without rewriting content`() {
        metadataStore.nextReservation =
            TraceReservation.Existing(contentSha256, TraceStorageState.Available)

        val result = repository.create(trace())

        assertEquals(TraceCreateResult.AlreadyExists, result)
        assertEquals(null, contentStore.lastWrite)
    }

    @Test
    fun `a reused idempotency key with different content conflicts`() {
        metadataStore.nextReservation =
            TraceReservation.Existing(ByteArray(32) { 9 }, TraceStorageState.Available)

        val result = repository.create(trace())

        assertEquals(TraceCreateResult.Conflict, result)
        assertEquals(null, contentStore.lastWrite)
    }

    @Test
    fun `a failed matching reservation is safely completed by a retry`() {
        metadataStore.nextReservation =
            TraceReservation.Existing(contentSha256, TraceStorageState.Failed)

        val result = repository.create(trace())

        assertEquals(TraceCreateResult.AlreadyExists, result)
        assertEquals(projectId to sessionId, metadataStore.available)
    }

    @Test
    fun `a content-store failure marks metadata failed and remains retryable`() {
        metadataStore.nextReservation = TraceReservation.Created
        contentStore.failure = IOException("object store unavailable")

        assertThrows(IOException::class.java) { repository.create(trace()) }
        assertEquals(projectId to sessionId, metadataStore.failed)
    }

    private fun trace() =
        StoredTrace(
            projectId = projectId,
            idempotencyKey = sessionId,
            metadata =
                ValidatedTraceMetadata(
                    schemaVersion = "1.0.0-alpha.1",
                    sessionId = sessionId,
                    startedAt = Instant.parse("2026-08-11T12:00:00Z"),
                    endedAt = null,
                    packageName = "dev.reprotrail.fixture",
                    captureMode = "internal",
                    actionCount = 1,
                ),
            content = "{\"trace\":true}".encodeToByteArray(),
            contentSha256 = contentSha256,
        )

    private class RecordingTraceMetadataStore : TraceMetadataStore {
        lateinit var nextReservation: TraceReservation
        var available: Pair<UUID, UUID>? = null
        var failed: Pair<UUID, UUID>? = null

        override fun reserve(record: TraceMetadataRecord): TraceReservation = nextReservation

        override fun markAvailable(projectId: UUID, sessionId: UUID) {
            available = projectId to sessionId
        }

        override fun markFailed(projectId: UUID, sessionId: UUID) {
            failed = projectId to sessionId
        }
    }

    private class RecordingTraceContentStore : TraceContentStore {
        var lastWrite: TraceContentWrite? = null
        var failure: IOException? = null

        override fun putIfAbsent(write: TraceContentWrite): TraceContentWriteResult {
            lastWrite = write
            failure?.let { throw it }
            return TraceContentWriteResult.Stored
        }
    }

    private companion object {
        val projectId: UUID = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
        val sessionId: UUID = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")
        val contentSha256: ByteArray = ByteArray(32) { it.toByte() }
    }
}
