package dev.reprotrail.server.persistence

import dev.reprotrail.server.security.StoredWorkerCredential
import dev.reprotrail.server.security.WorkerCredentialLookup
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcWorkerCredentialLookup(private val jdbc: JdbcClient) : WorkerCredentialLookup {
    override fun find(projectId: UUID, credentialId: UUID): StoredWorkerCredential? =
        jdbc.sql(
            """
            select token_digest, expires_at, revoked_at
            from worker_credentials
            where project_id = :projectId and id = :credentialId
            """.trimIndent(),
        ).param("projectId", projectId)
            .param("credentialId", credentialId)
            .query(::mapWorkerCredential)
            .optional()
            .orElse(null)
}

private fun mapWorkerCredential(resultSet: ResultSet, rowNumber: Int): StoredWorkerCredential {
    check(rowNumber >= 0)
    return StoredWorkerCredential(
        tokenDigest = resultSet.getBytes("token_digest"),
        expiresAt = resultSet.getObject("expires_at", OffsetDateTime::class.java)?.toInstant(),
        revokedAt = resultSet.getObject("revoked_at", OffsetDateTime::class.java)?.toInstant(),
    )
}
