package dev.reprotrail.server.access

import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class TraceAccessControllerTest {
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val sessionId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")
    private val trace =
        TraceMetadata(
            sessionId = sessionId,
            schemaVersion = "1.0.0-alpha.1",
            startedAt = Instant.parse("2026-08-11T12:00:00Z"),
            endedAt = Instant.parse("2026-08-11T12:00:01Z"),
            packageName = "dev.reprotrail.fixture",
            captureMode = "internal",
            actionCount = 1,
            createdAt = Instant.parse("2026-08-11T12:00:02Z"),
        )
    private val browser = RecordingBrowser(trace)
    private val downloader = RecordingDownloader()
    private val deleter = RecordingDeleter()
    private val mockMvc = MockMvcBuilders.standaloneSetup(TraceAccessController(browser, downloader, deleter)).build()

    @Test
    fun `list returns metadata and an opaque continuation cursor`() {
        browser.nextPage = TracePage(listOf(trace), TracePageCursor(trace.createdAt, trace.sessionId))

        val cursor =
            mockMvc.get("/v1/projects/$projectId/traces") {
                param("limit", "25")
            }.andExpect {
                status { isOk() }
                jsonPath("$.items[0].sessionId") { value(sessionId.toString()) }
                jsonPath("$.items[0].packageName") { value("dev.reprotrail.fixture") }
                jsonPath("$.nextCursor") { isNotEmpty() }
            }.andReturn().response.contentAsString.substringAfter("\"nextCursor\":\"").substringBefore('"')

        mockMvc.get("/v1/projects/$projectId/traces") { param("cursor", cursor) }
            .andExpect { status { isOk() } }
        assertEquals(TracePageCursor(trace.createdAt, trace.sessionId), browser.lastCursor)
    }

    @Test
    fun `metadata returns 404 for a tenant miss`() {
        browser.found = null

        mockMvc.get("/v1/projects/$projectId/traces/$sessionId").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("trace_not_found") }
        }
    }

    @Test
    fun `malformed pagination input returns 400 before browsing`() {
        mockMvc.get("/v1/projects/$projectId/traces") { param("cursor", "not-base64") }
            .andExpect { status { isBadRequest() } }
        mockMvc.get("/v1/projects/$projectId/traces") { param("limit", "101") }
            .andExpect { status { isBadRequest() } }

        assertEquals(0, browser.listCalls)
    }

    @Test
    fun `content download returns the immutable media type and attachment`() {
        downloader.result = TraceDownloadResult.Found("{\"trace\":true}".encodeToByteArray())
        val identity = dev.reprotrail.server.security.DeveloperIdentity(projectId, sessionId)
        val developerAuthentication = UsernamePasswordAuthenticationToken.authenticated(identity, null, emptyList())

        mockMvc.get("/v1/projects/$projectId/traces/$sessionId/content") {
            with { request ->
                request.userPrincipal = developerAuthentication
                request
            }
        }.andExpect {
            status { isOk() }
            content { contentType("application/vnd.reprotrail.trace+json") }
            content { bytes("{\"trace\":true}".encodeToByteArray()) }
            header { string("Content-Disposition", "attachment; filename=\"reprotrail-$sessionId.json\"") }
        }

        assertEquals(Triple(projectId, sessionId, sessionId), downloader.lastRequest)
    }

    @Test
    fun `delete returns no content and passes the developer audit identity`() {
        deleter.result = TraceDeletionResult.Deleted
        val identity = dev.reprotrail.server.security.DeveloperIdentity(projectId, sessionId)
        val authentication = UsernamePasswordAuthenticationToken.authenticated(identity, null, emptyList())

        mockMvc.delete("/v1/projects/$projectId/traces/$sessionId") {
            with { request ->
                request.userPrincipal = authentication
                request
            }
        }.andExpect { status { isNoContent() } }

        assertEquals(Triple(projectId, sessionId, sessionId), deleter.lastRequest)
        assertEquals(TraceAuditAction.Deleted, deleter.lastAction)
    }

    private class RecordingBrowser(initialTrace: TraceMetadata) : TraceBrowser {
        var nextPage = TracePage(emptyList(), null)
        var found: TraceMetadata? = initialTrace
        var lastCursor: TracePageCursor? = null
        var listCalls = 0

        override fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage {
            listCalls += 1
            lastCursor = cursor
            return nextPage
        }

        override fun find(projectId: UUID, sessionId: UUID): TraceMetadata? = found
    }

    private class RecordingDownloader : TraceDownloader {
        var result: TraceDownloadResult = TraceDownloadResult.NotFound
        var lastRequest: Triple<UUID, UUID, UUID>? = null

        override fun download(projectId: UUID, sessionId: UUID, actorCredentialId: UUID): TraceDownloadResult {
            lastRequest = Triple(projectId, sessionId, actorCredentialId)
            return result
        }
    }

    private class RecordingDeleter : TraceDeleter {
        var result: TraceDeletionResult = TraceDeletionResult.NotFound
        var lastRequest: Triple<UUID, UUID, UUID>? = null
        var lastAction: TraceAuditAction? = null

        override fun delete(
            projectId: UUID,
            sessionId: UUID,
            actorCredentialId: UUID?,
            auditAction: TraceAuditAction,
        ): TraceDeletionResult {
            lastRequest = Triple(projectId, sessionId, checkNotNull(actorCredentialId))
            lastAction = auditAction
            return result
        }
    }
}
