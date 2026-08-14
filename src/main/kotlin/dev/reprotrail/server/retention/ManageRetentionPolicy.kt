package dev.reprotrail.server.retention

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class ProjectRetentionPolicy(
    val projectId: UUID,
    val retainForDays: Int,
    val customized: Boolean,
    val updatedAt: Instant?,
    val updatedBy: UUID?,
)

internal data class RetentionPolicyUpdate(
    val projectId: UUID,
    val retainForDays: Int,
    val adminCredentialId: UUID,
    val updatedAt: Instant,
)

internal interface RetentionPolicyStore {
    fun find(projectId: UUID): ProjectRetentionPolicy?

    fun update(update: RetentionPolicyUpdate): ProjectRetentionPolicy
}

internal interface RetentionPolicyManager {
    fun find(projectId: UUID): ProjectRetentionPolicy

    fun update(projectId: UUID, retainForDays: Int, adminCredentialId: UUID): ProjectRetentionPolicy
}

internal class ManageRetentionPolicy(
    private val store: RetentionPolicyStore,
    defaultRetainFor: Duration,
    private val clock: Clock,
) : RetentionPolicyManager {
    private val defaultRetainForDays = defaultRetainFor.toWholeDays()

    override fun find(projectId: UUID): ProjectRetentionPolicy =
        store.find(projectId)
            ?: ProjectRetentionPolicy(projectId, defaultRetainForDays, customized = false, updatedAt = null, updatedBy = null)

    override fun update(projectId: UUID, retainForDays: Int, adminCredentialId: UUID): ProjectRetentionPolicy {
        require(retainForDays in MINIMUM_RETENTION_DAYS..MAXIMUM_RETENTION_DAYS) {
            "Retention must be between $MINIMUM_RETENTION_DAYS and $MAXIMUM_RETENTION_DAYS days."
        }
        return store.update(RetentionPolicyUpdate(projectId, retainForDays, adminCredentialId, clock.instant()))
    }

    private fun Duration.toWholeDays(): Int {
        val days = toDays()
        require(days in MINIMUM_RETENTION_DAYS.toLong()..MAXIMUM_RETENTION_DAYS.toLong()) {
            "Default retention is outside supported bounds."
        }
        require(this == Duration.ofDays(days)) { "Default retention must be expressed in whole days." }
        return days.toInt()
    }

    private companion object {
        const val MINIMUM_RETENTION_DAYS = 1
        const val MAXIMUM_RETENTION_DAYS = 3_650
    }
}
