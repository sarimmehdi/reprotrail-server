package dev.reprotrail.server.replay

import dev.reprotrail.server.security.WorkerAuthorizer
import dev.reprotrail.server.security.WorkerIdentity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class WorkerAuthenticationFilterTest {
    private val projectId = UUID.randomUUID()
    private val workerId = UUID.randomUUID()
    private val filter =
        WorkerAuthenticationFilter(
            WorkerAuthorizer { candidateProject, token ->
                WorkerIdentity(projectId, workerId).takeIf {
                    candidateProject == projectId && token == "valid-worker-token"
                }
            },
        )

    @Test
    fun `invalid worker credentials stop internal replay access`() {
        val exchange = exchange(null)

        assertEquals(401, exchange.response.status)
        assertFalse(exchange.chainInvoked)
    }

    @Test
    fun `valid worker credentials establish only work authority`() {
        val exchange = exchange("valid-worker-token")

        val authentication = checkNotNull(SecurityContextHolder.getContext().authentication)
        assertTrue(exchange.chainInvoked)
        assertEquals(WorkerIdentity(projectId, workerId), authentication.principal)
        assertEquals(listOf(REPLAY_WORK_AUTHORITY), authentication.authorities.map { it.authority })
    }

    private fun exchange(token: String?): Exchange {
        SecurityContextHolder.clearContext()
        val request = MockHttpServletRequest("POST", "/internal/v1/projects/$projectId/replay-jobs/lease")
        token?.let { request.addHeader("Authorization", "Bearer $it") }
        val response = MockHttpServletResponse()
        var chainInvoked = false
        filter.doFilter(request, response) { _, _ -> chainInvoked = true }
        return Exchange(response, chainInvoked)
    }

    private data class Exchange(val response: MockHttpServletResponse, val chainInvoked: Boolean)
}
