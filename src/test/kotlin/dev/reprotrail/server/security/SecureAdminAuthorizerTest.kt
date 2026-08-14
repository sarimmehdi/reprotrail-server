package dev.reprotrail.server.security

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecureAdminAuthorizerTest {
    private val now = Instant.parse("2026-08-15T00:00:00Z")
    private val projectId = UUID.randomUUID()
    private val credentialId = UUID.randomUUID()
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val token = "rt_admin_$credentialId.$secret"
    private val digester = HmacTokenDigester(ByteArray(32) { (it + 32).toByte() })
    private val lookup = RecordingAdminLookup()
    private val authorizer = SecureAdminAuthorizer(lookup, digester, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `matching live admin credential returns a project scoped audit identity`() {
        lookup.result = StoredAdminCredential(digester.digest(secret), now.plusSeconds(60), null)

        assertEquals(AdminIdentity(projectId, credentialId), authorizer.authorize(projectId, token))
        assertEquals(projectId to credentialId, lookup.lastQuery)
    }

    @Test
    fun `developer and worker tokens cannot gain administrative authority`() {
        lookup.result = StoredAdminCredential(digester.digest(secret), now.plusSeconds(60), null)

        assertNull(authorizer.authorize(projectId, "rt_dev_$credentialId.$secret"))
        assertNull(authorizer.authorize(projectId, "rt_worker_$credentialId.$secret"))
        assertNull(lookup.lastQuery)
    }

    @Test
    fun `revoked expired and mismatched admin credentials are rejected`() {
        lookup.result = StoredAdminCredential(digester.digest(secret), now.plusSeconds(60), now.minusSeconds(1))
        assertNull(authorizer.authorize(projectId, token))
        lookup.result = StoredAdminCredential(digester.digest(secret), now, null)
        assertNull(authorizer.authorize(projectId, token))
        lookup.result = StoredAdminCredential(digester.digest("different"), now.plusSeconds(60), null)
        assertNull(authorizer.authorize(projectId, token))
    }

    private class RecordingAdminLookup : AdminCredentialLookup {
        var result: StoredAdminCredential? = null
        var lastQuery: Pair<UUID, UUID>? = null

        override fun find(projectId: UUID, credentialId: UUID): StoredAdminCredential? {
            lastQuery = projectId to credentialId
            return result
        }
    }
}
