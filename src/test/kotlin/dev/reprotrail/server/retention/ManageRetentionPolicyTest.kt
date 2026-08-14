package dev.reprotrail.server.retention

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManageRetentionPolicyTest {
    private val now = Instant.parse("2026-08-15T00:00:00Z")
    private val projectId = UUID.randomUUID()
    private val adminId = UUID.randomUUID()
    private val store = RecordingRetentionPolicyStore()
    private val manager = ManageRetentionPolicy(store, Duration.ofDays(30), Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `read returns the effective default when a project has no override`() {
        assertEquals(
            ProjectRetentionPolicy(projectId, 30, customized = false, updatedAt = null, updatedBy = null),
            manager.find(projectId),
        )
    }

    @Test
    fun `update validates and persists an auditable project override`() {
        val expected = ProjectRetentionPolicy(projectId, 90, true, now, adminId)
        store.saved = expected

        assertEquals(expected, manager.update(projectId, 90, adminId))
        assertEquals(RetentionPolicyUpdate(projectId, 90, adminId, now), store.lastUpdate)
        assertFailsWith<IllegalArgumentException> { manager.update(projectId, 0, adminId) }
        assertFailsWith<IllegalArgumentException> { manager.update(projectId, 3_651, adminId) }
    }

    private class RecordingRetentionPolicyStore : RetentionPolicyStore {
        var saved: ProjectRetentionPolicy? = null
        var lastUpdate: RetentionPolicyUpdate? = null

        override fun find(projectId: UUID): ProjectRetentionPolicy? = null

        override fun update(update: RetentionPolicyUpdate): ProjectRetentionPolicy {
            lastUpdate = update
            return checkNotNull(saved)
        }
    }
}
