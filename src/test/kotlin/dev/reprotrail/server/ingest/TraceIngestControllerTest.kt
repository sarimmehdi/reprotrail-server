package dev.reprotrail.server.ingest

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class TraceIngestControllerTest {
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val sessionId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")
    private var capturedRequest: IngestRequest? = null
    private var nextOutcome: IngestOutcome =
        IngestOutcome.Created(IngestedTrace(sessionId, "1.0.0-alpha.1", 1))
    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(TraceIngestController { request ->
                capturedRequest = request
                nextOutcome
            }).build()

    @Test
    fun `POST maps transport fields and creates a trace`() {
        mockMvc
            .post("/v1/projects/$projectId/traces") {
                header("Authorization", "Bearer ingest-secret")
                header("Idempotency-Key", sessionId.toString())
                contentType = MediaType.APPLICATION_JSON
                content = "{\"trace\":true}"
            }.andExpect {
                status { isCreated() }
                header { string("Location", "/v1/projects/$projectId/traces/$sessionId") }
                jsonPath("$.sessionId") { value(sessionId.toString()) }
                jsonPath("$.schemaVersion") { value("1.0.0-alpha.1") }
                jsonPath("$.actionCount") { value(1) }
            }

        val request = checkNotNull(capturedRequest)
        assertEquals(projectId, request.projectId)
        assertEquals("ingest-secret", request.token)
        assertEquals(sessionId, request.idempotencyKey)
        assertArrayEquals("{\"trace\":true}".encodeToByteArray(), request.content)
    }

    @Test
    fun `an identical retry returns 200`() {
        nextOutcome = IngestOutcome.AlreadyExists(IngestedTrace(sessionId, "1.0.0-alpha.1", 1))

        authorizedPost().andExpect { status { isOk() } }
    }

    @Test
    fun `conflicting bytes return 409`() {
        nextOutcome = IngestOutcome.Conflict

        authorizedPost().andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("idempotency_conflict") }
        }
    }

    @Test
    fun `missing bearer credentials return 401 before calling the use case`() {
        mockMvc
            .post("/v1/projects/$projectId/traces") {
                header("Idempotency-Key", sessionId.toString())
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect { status { isUnauthorized() } }

        assertNull(capturedRequest)
    }

    @Test
    fun `a malformed idempotency key returns 400 before calling the use case`() {
        mockMvc
            .post("/v1/projects/$projectId/traces") {
                header("Authorization", "Bearer ingest-secret")
                header("Idempotency-Key", "not-a-uuid")
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect { status { isBadRequest() } }

        assertNull(capturedRequest)
    }

    private fun authorizedPost() =
        mockMvc.post("/v1/projects/$projectId/traces") {
            header("Authorization", "Bearer ingest-secret")
            header("Idempotency-Key", sessionId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }
}
