package dev.reprotrail.server.security

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal data class StoredWorkerCredential(
    val tokenDigest: ByteArray,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
)

internal data class WorkerIdentity(
    val projectId: UUID,
    val credentialId: UUID,
)

internal fun interface WorkerCredentialLookup {
    fun find(projectId: UUID, credentialId: UUID): StoredWorkerCredential?
}

internal fun interface WorkerAuthorizer {
    fun authorize(projectId: UUID, token: String): WorkerIdentity?
}

internal class SecureWorkerAuthorizer(
    private val lookup: WorkerCredentialLookup,
    private val digester: HmacTokenDigester,
    private val clock: Clock,
) : WorkerAuthorizer {
    override fun authorize(projectId: UUID, token: String): WorkerIdentity? {
        val parsed = token.parseWorkerToken() ?: return null
        val credential = lookup.find(projectId, parsed.credentialId) ?: return null
        val now = clock.instant()
        if (credential.revokedAt != null || credential.expiresAt?.isAfter(now) == false) return null
        if (!MessageDigest.isEqual(credential.tokenDigest, digester.digest(parsed.secret))) return null
        return WorkerIdentity(projectId, parsed.credentialId)
    }
}

private data class ParsedWorkerToken(val credentialId: UUID, val secret: String)

private fun String.parseWorkerToken(): ParsedWorkerToken? {
    if (!startsWith(WORKER_TOKEN_PREFIX)) return null
    val separator = indexOf('.', WORKER_TOKEN_PREFIX.length)
    if (separator < 0 || separator == lastIndex) return null
    val credentialId = runCatching { UUID.fromString(substring(WORKER_TOKEN_PREFIX.length, separator)) }.getOrNull()
        ?: return null
    val secret = substring(separator + 1)
    val bytes = runCatching { Base64.getUrlDecoder().decode(secret) }.getOrNull() ?: return null
    if (bytes.size < MINIMUM_WORKER_SECRET_BYTES) return null
    return ParsedWorkerToken(credentialId, secret)
}

private const val WORKER_TOKEN_PREFIX = "rt_worker_"
private const val MINIMUM_WORKER_SECRET_BYTES = 32
