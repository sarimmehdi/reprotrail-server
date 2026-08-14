package dev.reprotrail.server.security

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal data class StoredAdminCredential(
    val tokenDigest: ByteArray,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
)

internal data class AdminIdentity(
    val projectId: UUID,
    val credentialId: UUID,
)

internal fun interface AdminCredentialLookup {
    fun find(projectId: UUID, credentialId: UUID): StoredAdminCredential?
}

internal fun interface AdminAuthorizer {
    fun authorize(projectId: UUID, token: String): AdminIdentity?
}

internal class SecureAdminAuthorizer(
    private val lookup: AdminCredentialLookup,
    private val digester: HmacTokenDigester,
    private val clock: Clock,
) : AdminAuthorizer {
    override fun authorize(projectId: UUID, token: String): AdminIdentity? {
        val parsed = token.parseAdminToken() ?: return null
        val credential = lookup.find(projectId, parsed.credentialId) ?: return null
        val now = clock.instant()
        if (credential.revokedAt != null || credential.expiresAt?.isAfter(now) == false) return null
        if (!MessageDigest.isEqual(credential.tokenDigest, digester.digest(parsed.secret))) return null
        return AdminIdentity(projectId, parsed.credentialId)
    }
}

private data class ParsedAdminToken(val credentialId: UUID, val secret: String)

private fun String.parseAdminToken(): ParsedAdminToken? {
    if (!startsWith(ADMIN_TOKEN_PREFIX)) return null
    val separator = indexOf('.', ADMIN_TOKEN_PREFIX.length)
    if (separator < 0 || separator == lastIndex) return null
    val credentialId = runCatching { UUID.fromString(substring(ADMIN_TOKEN_PREFIX.length, separator)) }.getOrNull()
        ?: return null
    val secret = substring(separator + 1)
    val bytes = runCatching { Base64.getUrlDecoder().decode(secret) }.getOrNull() ?: return null
    if (bytes.size < MINIMUM_ADMIN_SECRET_BYTES) return null
    return ParsedAdminToken(credentialId, secret)
}

private const val ADMIN_TOKEN_PREFIX = "rt_admin_"
private const val MINIMUM_ADMIN_SECRET_BYTES = 32
