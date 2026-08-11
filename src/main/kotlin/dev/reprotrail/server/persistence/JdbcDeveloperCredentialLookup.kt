package dev.reprotrail.server.persistence

import dev.reprotrail.server.security.DeveloperCredentialLookup
import dev.reprotrail.server.security.StoredDeveloperCredential
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcDeveloperCredentialLookup(
    private val jdbc: JdbcClient,
) : DeveloperCredentialLookup {
    override fun find(projectId: UUID, credentialId: UUID): StoredDeveloperCredential? =
        jdbc.sql(
            """
            select token_digest, expires_at, revoked_at
            from developer_credentials
            where project_id = :projectId and id = :credentialId
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("credentialId", credentialId)
            .query { resultSet, _ ->
                StoredDeveloperCredential(
                    tokenDigest = resultSet.getBytes("token_digest"),
                    expiresAt = resultSet.getObject("expires_at", OffsetDateTime::class.java)?.toInstant(),
                    revokedAt = resultSet.getObject("revoked_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }.optional()
            .orElse(null)
}
