package dev.reprotrail.server.retention

import dev.reprotrail.server.security.AdminAuthorizer
import dev.reprotrail.server.security.AdminIdentity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class AdminAuthenticationFilterTest {
    private val projectId = UUID.randomUUID()
    private val credentialId = UUID.randomUUID()
    private val filter =
        AdminAuthenticationFilter(
            AdminAuthorizer { candidateProject, token ->
                AdminIdentity(projectId, credentialId).takeIf {
                    candidateProject == projectId && token == "valid-admin-token"
                }
            },
        )

    @Test
    fun `valid admin credential establishes only retention administration authority`() {
        val exchange = exchange("PUT", "/v1/projects/$projectId/retention-policy", "valid-admin-token")
        val authentication = checkNotNull(SecurityContextHolder.getContext().authentication)

        assertTrue(exchange.chainInvoked)
        assertEquals(AdminIdentity(projectId, credentialId), authentication.principal)
        assertEquals(
            listOf(RETENTION_ADMIN_AUTHORITY),
            authentication.authorities.map { it.authority },
        )
    }

    @Test
    fun `missing admin credential stops retention access before MVC`() {
        val exchange = exchange("GET", "/v1/projects/$projectId/retention-policy", null)

        assertFalse(exchange.chainInvoked)
        assertEquals(401, exchange.response.status)
    }

    @Test
    fun `admin filter ignores developer replay and trace routes`() {
        assertTrue(exchange("GET", "/v1/projects/$projectId/traces", null).chainInvoked)
        assertTrue(exchange("POST", "/v1/projects/$projectId/traces/${UUID.randomUUID()}/replay-jobs", null).chainInvoked)
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

    private data class Exchange(val response: MockHttpServletResponse, val chainInvoked: Boolean)
}
