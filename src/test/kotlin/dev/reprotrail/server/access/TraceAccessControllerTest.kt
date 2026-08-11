package dev.reprotrail.server.access

import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
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
    private val mockMvc = MockMvcBuilders.standaloneSetup(TraceAccessController(browser)).build()

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
}
