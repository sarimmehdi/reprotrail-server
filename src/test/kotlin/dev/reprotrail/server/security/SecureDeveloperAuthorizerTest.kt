package dev.reprotrail.server.security

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SecureDeveloperAuthorizerTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val projectId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f400")
    private val credentialId = UUID.fromString("018f1f4e-7b2a-7c81-9f8d-9d9dd7f3f402")
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val token = "rt_dev_$credentialId.$secret"
    private val digester = HmacTokenDigester(ByteArray(32) { (it + 32).toByte() })
    private val lookup = RecordingLookup()
    private val authorizer = SecureDeveloperAuthorizer(lookup, digester, clock)

    @Test
    fun `matching live developer credential returns an auditable identity`() {
        lookup.result = StoredDeveloperCredential(digester.digest(secret), now.plusSeconds(60), null)

        assertEquals(DeveloperIdentity(projectId, credentialId), authorizer.authorize(projectId, token))
        assertEquals(projectId to credentialId, lookup.lastQuery)
    }

    @Test
    fun `ingest token cannot gain developer authority`() {
        lookup.result = StoredDeveloperCredential(digester.digest(secret), now.plusSeconds(60), null)

        assertNull(authorizer.authorize(projectId, "rt_ingest_$credentialId.$secret"))
        assertNull(lookup.lastQuery)
    }

    @Test
    fun `revoked expired and mismatched credentials are rejected`() {
        lookup.result = StoredDeveloperCredential(digester.digest(secret), now.plusSeconds(60), now.minusSeconds(1))
        assertNull(authorizer.authorize(projectId, token))

        lookup.result = StoredDeveloperCredential(digester.digest(secret), now, null)
        assertNull(authorizer.authorize(projectId, token))

        lookup.result = StoredDeveloperCredential(digester.digest("different"), now.plusSeconds(60), null)
        assertNull(authorizer.authorize(projectId, token))
    }

    private class RecordingLookup : DeveloperCredentialLookup {
        var result: StoredDeveloperCredential? = null
        var lastQuery: Pair<UUID, UUID>? = null

        override fun find(projectId: UUID, credentialId: UUID): StoredDeveloperCredential? {
            lastQuery = projectId to credentialId
            return result
        }
    }
}
