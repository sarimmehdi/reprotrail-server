package dev.reprotrail.server.ingest

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class IngestAuthenticationFilterTest {
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val authorizer = RecordingAuthorizer(projectId, "valid-token")
    private val filter = IngestAuthenticationFilter(authorizer, 1_024)

    @Test
    fun `missing bearer credentials are rejected before the body reaches MVC`() {
        val exchange = exchange(token = null, contentLength = 512)

        assertEquals(401, exchange.response.status)
        assertFalse(exchange.chainInvoked)
        assertEquals(0, authorizer.calls)
    }

    @Test
    fun `invalid credentials are rejected before the body reaches MVC`() {
        val exchange = exchange(token = "wrong-token", contentLength = 512)

        assertEquals(401, exchange.response.status)
        assertFalse(exchange.chainInvoked)
        assertEquals(1, authorizer.calls)
    }

    @Test
    fun `an oversized authenticated trace is rejected before the body reaches MVC`() {
        val exchange = exchange(token = "valid-token", contentLength = 1_025)

        assertEquals(413, exchange.response.status)
        assertFalse(exchange.chainInvoked)
    }

    @Test
    fun `chunked ingest is rejected because its size cannot be checked before parsing`() {
        val exchange = exchange(token = "valid-token", contentLength = -1)

        assertEquals(411, exchange.response.status)
        assertFalse(exchange.chainInvoked)
    }

    @Test
    fun `valid credentials establish ingest authority and continue the chain`() {
        val exchange = exchange(token = "valid-token", contentLength = 512)

        assertEquals(200, exchange.response.status)
        assertTrue(exchange.chainInvoked)
        assertTrue(
            checkNotNull(SecurityContextHolder.getContext().authentication).authorities.any {
                it.authority == INGEST_AUTHORITY
            },
        )
    }

    private fun exchange(token: String?, contentLength: Long): Exchange {
        SecurityContextHolder.clearContext()
        val request = MockHttpServletRequest("POST", "/v1/projects/$projectId/traces").apply {
            token?.let { addHeader("Authorization", "Bearer $it") }
            if (contentLength >= 0) {
                setContent(ByteArray(contentLength.toInt()))
            }
        }
        val response = MockHttpServletResponse()
        var chainInvoked = false
        filter.doFilter(request, response) { _, _ -> chainInvoked = true }
        return Exchange(response, chainInvoked)
    }

    private data class Exchange(
        val response: MockHttpServletResponse,
        val chainInvoked: Boolean,
    )

    private class RecordingAuthorizer(
        private val projectId: UUID,
        private val token: String,
    ) : IngestAuthorizer {
        var calls = 0

        override fun isAuthorized(projectId: UUID, token: String): Boolean {
            calls += 1
            return projectId == this.projectId && token == this.token
        }
    }
}
