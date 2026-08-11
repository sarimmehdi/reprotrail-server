package dev.reprotrail.server.security

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecureWorkerAuthorizerTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val projectId = UUID.randomUUID()
    private val credentialId = UUID.randomUUID()
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val digester = HmacTokenDigester(ByteArray(32) { (it + 32).toByte() })
    private val lookup = WorkerCredentialLookup { _, _ -> StoredWorkerCredential(digester.digest(secret), null, null) }
    private val authorizer = SecureWorkerAuthorizer(lookup, digester, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `matching worker token returns project scoped identity`() {
        assertEquals(WorkerIdentity(projectId, credentialId), authorizer.authorize(projectId, "rt_worker_$credentialId.$secret"))
    }

    @Test
    fun `developer token cannot gain worker authority`() {
        assertNull(authorizer.authorize(projectId, "rt_dev_$credentialId.$secret"))
    }
}
