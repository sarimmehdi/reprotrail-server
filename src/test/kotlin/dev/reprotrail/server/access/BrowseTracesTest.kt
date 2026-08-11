package dev.reprotrail.server.access

import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BrowseTracesTest {
    private val catalog = RecordingCatalog()
    private val browser = BrowseTraces(catalog)
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val sessionId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f441")

    @Test
    fun `browse delegates a tenant cursor and bounded page size`() {
        val cursor = TracePageCursor(Instant.parse("2026-08-11T12:00:00Z"), sessionId)

        browser.list(projectId, cursor, 25)

        assertEquals(Triple(projectId, cursor, 25), catalog.lastList)
    }

    @Test
    fun `browse rejects unbounded page sizes before persistence`() {
        assertThrows(IllegalArgumentException::class.java) { browser.list(projectId, null, 101) }
        assertEquals(null, catalog.lastList)
    }

    @Test
    fun `metadata lookup remains tenant scoped`() {
        browser.find(projectId, sessionId)

        assertEquals(projectId to sessionId, catalog.lastFind)
    }

    private class RecordingCatalog : TraceCatalog {
        var lastList: Triple<UUID, TracePageCursor?, Int>? = null
        var lastFind: Pair<UUID, UUID>? = null

        override fun list(projectId: UUID, cursor: TracePageCursor?, limit: Int): TracePage {
            lastList = Triple(projectId, cursor, limit)
            return TracePage(emptyList(), null)
        }

        override fun find(projectId: UUID, sessionId: UUID): TraceMetadata? {
            lastFind = projectId to sessionId
            return null
        }
    }
}
