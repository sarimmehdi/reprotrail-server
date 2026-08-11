package dev.reprotrail.server.access

import dev.reprotrail.server.security.DeveloperAuthorizer
import dev.reprotrail.server.security.DeveloperIdentity
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class DeveloperAuthenticationFilterTest {
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val credentialId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f402")
    private val authorizer = DeveloperAuthorizer { candidateProject, token ->
        DeveloperIdentity(projectId, credentialId).takeIf {
            candidateProject == projectId && token == "valid-developer-token"
        }
    }
    private val filter = DeveloperAuthenticationFilter(authorizer)

    @Test
    fun `missing or invalid developer credentials stop access before MVC`() {
        listOf(null, "wrong-token").forEach { token ->
            val exchange = exchange("GET", "/v1/projects/$projectId/traces", token)

            assertEquals(401, exchange.response.status)
            assertFalse(exchange.chainInvoked)
        }
    }

    @Test
    fun `valid credentials establish read delete and audit identity`() {
        val exchange = exchange("DELETE", "/v1/projects/$projectId/traces/$credentialId", "valid-developer-token")

        val authentication = checkNotNull(SecurityContextHolder.getContext().authentication)
        assertTrue(exchange.chainInvoked)
        assertEquals(DeveloperIdentity(projectId, credentialId), authentication.principal)
        assertEquals(
            setOf(TRACE_READ_AUTHORITY, TRACE_DELETE_AUTHORITY, REPLAY_CREATE_AUTHORITY, REPLAY_READ_AUTHORITY),
            authentication.authorities.map { it.authority }.toSet(),
        )
    }

    @Test
    fun `ingestion requests remain outside the developer filter`() {
        val exchange = exchange("POST", "/v1/projects/$projectId/traces", null)

        assertTrue(exchange.chainInvoked)
    }

    @Test
    fun `replay creation requests receive developer authority`() {
        val traceId = UUID.randomUUID()
        val exchange =
            exchange("POST", "/v1/projects/$projectId/traces/$traceId/replay-jobs", "valid-developer-token")

        assertTrue(exchange.chainInvoked)
        assertEquals(
            DeveloperIdentity(projectId, credentialId),
            checkNotNull(SecurityContextHolder.getContext().authentication).principal,
        )
    }

    private fun exchange(method: String, path: String, token: String?): Exchange {
        SecurityContextHolder.clearContext()
        val request = MockHttpServletRequest(method, path)
        token?.let { request.addHeader("Authorization", "Bearer $it") }
        val response = MockHttpServletResponse()
        var chainInvoked = false
        filter.doFilter(request, response) { _, _ -> chainInvoked = true }
        return Exchange(response, chainInvoked)
    }

    private data class Exchange(
        val response: MockHttpServletResponse,
        val chainInvoked: Boolean,
    )
}
