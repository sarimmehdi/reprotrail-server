package dev.reprotrail.server.persistence

import dev.reprotrail.server.security.IngestCredentialLookup
import dev.reprotrail.server.security.StoredIngestCredential
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcIngestCredentialLookup(
    private val jdbc: JdbcClient,
) : IngestCredentialLookup {
    override fun find(projectId: UUID, credentialId: UUID): StoredIngestCredential? =
        jdbc.sql(
            """
            select token_digest, expires_at, revoked_at
            from ingest_credentials
            where project_id = :projectId and id = :credentialId
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("credentialId", credentialId)
            .query { resultSet, _ ->
                StoredIngestCredential(
                    tokenDigest = resultSet.getBytes("token_digest"),
                    expiresAt = resultSet.getObject("expires_at", OffsetDateTime::class.java)?.toInstant(),
                    revokedAt = resultSet.getObject("revoked_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }.optional()
            .orElse(null)
}
