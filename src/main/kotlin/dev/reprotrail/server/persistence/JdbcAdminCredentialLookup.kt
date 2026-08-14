package dev.reprotrail.server.persistence

import dev.reprotrail.server.security.AdminCredentialLookup
import dev.reprotrail.server.security.StoredAdminCredential
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcAdminCredentialLookup(
    private val jdbc: JdbcClient,
) : AdminCredentialLookup {
    override fun find(projectId: UUID, credentialId: UUID): StoredAdminCredential? =
        jdbc.sql(
            """
            select token_digest, expires_at, revoked_at
            from admin_credentials
            where project_id = :projectId and id = :credentialId
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("credentialId", credentialId)
            .query { resultSet, _ ->
                StoredAdminCredential(
                    tokenDigest = resultSet.getBytes("token_digest"),
                    expiresAt = resultSet.getObject("expires_at", OffsetDateTime::class.java)?.toInstant(),
                    revokedAt = resultSet.getObject("revoked_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }.optional()
            .orElse(null)
}
