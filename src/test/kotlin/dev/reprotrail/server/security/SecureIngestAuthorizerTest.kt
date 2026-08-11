package dev.reprotrail.server.security

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureIngestAuthorizerTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val credentialId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f401")
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val token = "rt_ingest_$credentialId.$secret"
    private val digester = HmacTokenDigester(ByteArray(32) { (it + 32).toByte() })
    private val lookup = RecordingCredentialLookup()
    private val authorizer = SecureIngestAuthorizer(lookup, digester, clock)

    @Test
    fun `a live credential with a matching digest is authorized`() {
        lookup.result = credential(digest = digester.digest(secret))

        assertTrue(authorizer.isAuthorized(projectId, token))
        assertEquals(projectId to credentialId, lookup.lastQuery)
    }

    @Test
    fun `a token with a different secret is rejected`() {
        lookup.result = credential(digest = digester.digest("another-secret"))

        assertFalse(authorizer.isAuthorized(projectId, token))
    }

    @Test
    fun `a revoked credential is rejected`() {
        lookup.result = credential(digest = digester.digest(secret), revokedAt = now.minusSeconds(1))

        assertFalse(authorizer.isAuthorized(projectId, token))
    }

    @Test
    fun `an expired credential is rejected`() {
        lookup.result = credential(digest = digester.digest(secret), expiresAt = now)

        assertFalse(authorizer.isAuthorized(projectId, token))
    }

    @Test
    fun `a malformed token is rejected without querying persistence`() {
        assertFalse(authorizer.isAuthorized(projectId, "not-an-ingest-token"))
        assertEquals(null, lookup.lastQuery)
    }

    private fun credential(
        digest: ByteArray,
        expiresAt: Instant? = now.plusSeconds(60),
        revokedAt: Instant? = null,
    ) = StoredIngestCredential(digest, expiresAt, revokedAt)

    private class RecordingCredentialLookup : IngestCredentialLookup {
        var result: StoredIngestCredential? = null
        var lastQuery: Pair<UUID, UUID>? = null

        override fun find(projectId: UUID, credentialId: UUID): StoredIngestCredential? {
            lastQuery = projectId to credentialId
            return result
        }
    }
}
