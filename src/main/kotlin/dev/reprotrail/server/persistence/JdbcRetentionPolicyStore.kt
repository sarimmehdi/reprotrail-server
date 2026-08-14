package dev.reprotrail.server.persistence

import dev.reprotrail.server.retention.ProjectRetentionPolicy
import dev.reprotrail.server.retention.RetentionPolicyStore
import dev.reprotrail.server.retention.RetentionPolicyUpdate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

internal class JdbcRetentionPolicyStore(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) : RetentionPolicyStore {
    override fun find(projectId: UUID): ProjectRetentionPolicy? =
        jdbc.sql(
            """
            select project_id, retain_for_days, updated_at, updated_by_admin_credential_id
            from project_retention_policies
            where project_id = :projectId
            """.trimIndent(),
        ).param("projectId", projectId)
            .query(::mapPolicy)
            .optional()
            .orElse(null)

    override fun update(update: RetentionPolicyUpdate): ProjectRetentionPolicy =
        checkNotNull(
            transactions.execute {
                val policy =
                    jdbc.sql(
                        """
                        insert into project_retention_policies (
                            project_id, retain_for_days, updated_by_admin_credential_id, created_at, updated_at
                        ) values (
                            :projectId, :retainForDays, :adminId, :updatedAt, :updatedAt
                        )
                        on conflict (project_id) do update
                        set retain_for_days = excluded.retain_for_days,
                            updated_by_admin_credential_id = excluded.updated_by_admin_credential_id,
                            updated_at = excluded.updated_at
                        returning project_id, retain_for_days, updated_at, updated_by_admin_credential_id
                        """.trimIndent(),
                    ).param("projectId", update.projectId)
                        .param("retainForDays", update.retainForDays)
                        .param("adminId", update.adminCredentialId)
                        .param("updatedAt", update.updatedAt.atOffset(ZoneOffset.UTC))
                        .query(::mapPolicy)
                        .single()
                jdbc.sql(
                    """
                    insert into audit_events (
                        id, project_id, actor_credential_id, action, occurred_at, details
                    ) values (
                        :id, :projectId, :adminId, 'retention_policy_updated', :updatedAt,
                        jsonb_build_object('retainForDays', :retainForDays)
                    )
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                    .param("projectId", update.projectId)
                    .param("adminId", update.adminCredentialId)
                    .param("retainForDays", update.retainForDays)
                    .param("updatedAt", update.updatedAt.atOffset(ZoneOffset.UTC))
                    .update()
                policy
            },
        ) { "Retention policy transaction did not execute." }
}

private fun mapPolicy(resultSet: java.sql.ResultSet, rowNumber: Int): ProjectRetentionPolicy {
    check(rowNumber >= 0)
    return ProjectRetentionPolicy(
        projectId = resultSet.getObject("project_id", UUID::class.java),
        retainForDays = resultSet.getInt("retain_for_days"),
        customized = true,
        updatedAt = resultSet.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        updatedBy = resultSet.getObject("updated_by_admin_credential_id", UUID::class.java),
    )
}
