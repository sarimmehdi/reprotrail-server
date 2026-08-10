package dev.reprotrail.server.ingest

import dev.reprotrail.server.contract.TraceContractValidator
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class IngestTraceUseCaseTest {
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val sessionId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")
    private val repository = InMemoryTraceRepository()
    private val authorizer = StubAuthorizer(projectId, "valid-token")
    private val useCase = IngestTraceUseCase(authorizer, TraceContractValidator(), repository)

    @Test
    fun `an authorized valid trace is created exactly once`() {
        val outcome = useCase.ingest(request())

        val created = assertInstanceOf(IngestOutcome.Created::class.java, outcome)
        assertEquals(sessionId, created.trace.sessionId)
        assertEquals(1, repository.records.size)
        assertEquals(validTrace(), repository.records.single().content.decodeToString())
    }

    @Test
    fun `an identical retry returns the original trace without another write`() {
        val first = useCase.ingest(request())
        val retry = useCase.ingest(request())

        assertInstanceOf(IngestOutcome.Created::class.java, first)
        assertInstanceOf(IngestOutcome.AlreadyExists::class.java, retry)
        assertEquals(1, repository.records.size)
    }

    @Test
    fun `the same idempotency key with different bytes conflicts`() {
        useCase.ingest(request())

        val outcome = useCase.ingest(request(content = validTrace() + "\n"))

        assertInstanceOf(IngestOutcome.Conflict::class.java, outcome)
        assertEquals(1, repository.records.size)
    }

    @Test
    fun `an idempotency key must equal the trace session id`() {
        val outcome = useCase.ingest(
            request(idempotencyKey = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f499")),
        )

        assertInstanceOf(IngestOutcome.IdempotencyMismatch::class.java, outcome)
        assertEquals(0, repository.records.size)
    }

    @Test
    fun `invalid traces never reach storage`() {
        val outcome = useCase.ingest(request(content = "{not-json"))

        val invalid = assertInstanceOf(IngestOutcome.InvalidTrace::class.java, outcome)
        assertEquals("parse.json", invalid.issues.single().rule)
        assertEquals(0, repository.records.size)
    }

    @Test
    fun `an unauthorized request is rejected before its body is validated`() {
        val outcome = useCase.ingest(request(token = "wrong-token", content = "{not-json"))

        assertInstanceOf(IngestOutcome.Unauthorized::class.java, outcome)
        assertEquals(0, repository.records.size)
    }

    private fun request(
        token: String = "valid-token",
        idempotencyKey: UUID = sessionId,
        content: String = validTrace(),
    ) = IngestRequest(projectId, token, idempotencyKey, content.encodeToByteArray())

    private fun validTrace(): String =
        checkNotNull(javaClass.getResource("/fixtures/v1alpha1/valid/minimal-tap.json")).readText()

    private class StubAuthorizer(
        private val projectId: UUID,
        private val token: String,
    ) : IngestAuthorizer {
        override fun isAuthorized(projectId: UUID, token: String): Boolean =
            projectId == this.projectId && token == this.token
    }

    private class InMemoryTraceRepository : TraceRepository {
        val records = mutableListOf<StoredTrace>()

        override fun create(record: StoredTrace): TraceCreateResult {
            val existing = records.singleOrNull {
                it.projectId == record.projectId && it.idempotencyKey == record.idempotencyKey
            }
            return when {
                existing == null -> {
                    records += record
                    TraceCreateResult.Created
                }
                existing.contentSha256.contentEquals(record.contentSha256) -> TraceCreateResult.AlreadyExists
                else -> TraceCreateResult.Conflict
            }
        }
    }
}
