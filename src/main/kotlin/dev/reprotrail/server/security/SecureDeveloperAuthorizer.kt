package dev.reprotrail.server.security

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal data class StoredDeveloperCredential(
    val tokenDigest: ByteArray,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
)

internal data class DeveloperIdentity(
    val projectId: UUID,
    val credentialId: UUID,
)

internal fun interface DeveloperCredentialLookup {
    fun find(projectId: UUID, credentialId: UUID): StoredDeveloperCredential?
}

internal fun interface DeveloperAuthorizer {
    fun authorize(projectId: UUID, token: String): DeveloperIdentity?
}

internal class SecureDeveloperAuthorizer(
    private val lookup: DeveloperCredentialLookup,
    private val digester: HmacTokenDigester,
    private val clock: Clock,
) : DeveloperAuthorizer {
    override fun authorize(projectId: UUID, token: String): DeveloperIdentity? {
        val parsed = token.parseDeveloperToken() ?: return null
        val credential = lookup.find(projectId, parsed.credentialId) ?: return null
        val now = clock.instant()
        if (credential.revokedAt != null || credential.expiresAt?.isAfter(now) == false) return null
        if (!MessageDigest.isEqual(credential.tokenDigest, digester.digest(parsed.secret))) return null
        return DeveloperIdentity(projectId, parsed.credentialId)
    }
}

private data class ParsedDeveloperToken(
    val credentialId: UUID,
    val secret: String,
)

private fun String.parseDeveloperToken(): ParsedDeveloperToken? {
    if (!startsWith(TOKEN_PREFIX)) return null
    val separator = indexOf('.', TOKEN_PREFIX.length)
    if (separator < 0 || separator == lastIndex) return null
    val credentialId = runCatching { UUID.fromString(substring(TOKEN_PREFIX.length, separator)) }.getOrNull()
        ?: return null
    val secret = substring(separator + 1)
    val secretBytes = runCatching { Base64.getUrlDecoder().decode(secret) }.getOrNull() ?: return null
    if (secretBytes.size < MINIMUM_TOKEN_SECRET_BYTES) return null
    return ParsedDeveloperToken(credentialId, secret)
}

private const val TOKEN_PREFIX = "rt_dev_"
private const val MINIMUM_TOKEN_SECRET_BYTES = 32
