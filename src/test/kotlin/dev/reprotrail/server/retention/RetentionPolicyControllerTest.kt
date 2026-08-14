package dev.reprotrail.server.retention

import dev.reprotrail.server.security.AdminIdentity
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class RetentionPolicyControllerTest {
    private val projectId = UUID.randomUUID()
    private val adminId = UUID.randomUUID()
    private val manager = RecordingRetentionPolicyManager()
    private val mockMvc = MockMvcBuilders.standaloneSetup(RetentionPolicyController(manager)).build()

    @Test
    fun `administrator reads effective policy metadata`() {
        manager.found = ProjectRetentionPolicy(projectId, 30, false, null, null)

        mockMvc.get("/v1/projects/$projectId/retention-policy").andExpect {
            status { isOk() }
            jsonPath("$.retainForDays") { value(30) }
            jsonPath("$.customized") { value(false) }
        }
    }

    @Test
    fun `administrator updates a bounded retention policy with audit identity`() {
        manager.updated =
            ProjectRetentionPolicy(projectId, 90, true, Instant.parse("2026-08-15T00:00:00Z"), adminId)

        mockMvc.put("/v1/projects/$projectId/retention-policy") {
            with { request ->
                request.userPrincipal =
                    UsernamePasswordAuthenticationToken.authenticated(
                        AdminIdentity(projectId, adminId),
                        null,
                        emptyList(),
                    )
                request
            }
            contentType = MediaType.APPLICATION_JSON
            content = """{"retainForDays":90}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.retainForDays") { value(90) }
            jsonPath("$.customized") { value(true) }
        }

        assertEquals(Triple(projectId, 90, adminId), manager.lastUpdate)
    }

    @Test
    fun `invalid policy returns 400 before persistence`() {
        mockMvc.put("/v1/projects/$projectId/retention-policy") {
            with { request ->
                request.userPrincipal =
                    UsernamePasswordAuthenticationToken.authenticated(
                        AdminIdentity(projectId, adminId),
                        null,
                        emptyList(),
                    )
                request
            }
            contentType = MediaType.APPLICATION_JSON
            content = """{"retainForDays":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_retention_policy") }
        }

        assertEquals(null, manager.lastUpdate)
    }

    private class RecordingRetentionPolicyManager : RetentionPolicyManager {
        var found: ProjectRetentionPolicy? = null
        var updated: ProjectRetentionPolicy? = null
        var lastUpdate: Triple<UUID, Int, UUID>? = null

        override fun find(projectId: UUID): ProjectRetentionPolicy = checkNotNull(found)

        override fun update(projectId: UUID, retainForDays: Int, adminCredentialId: UUID): ProjectRetentionPolicy {
            lastUpdate = Triple(projectId, retainForDays, adminCredentialId)
            return checkNotNull(updated)
        }
    }
}
