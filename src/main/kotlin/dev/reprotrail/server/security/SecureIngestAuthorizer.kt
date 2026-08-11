package dev.reprotrail.server.security

import dev.reprotrail.server.ingest.IngestAuthorizer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class StoredIngestCredential(
    val tokenDigest: ByteArray,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
)

internal fun interface IngestCredentialLookup {
    fun find(projectId: UUID, credentialId: UUID): StoredIngestCredential?
}

internal class HmacTokenDigester(
    pepper: ByteArray,
) {
    private val key = SecretKeySpec(pepper.copyOf(), HMAC_ALGORITHM)

    init {
        require(pepper.size >= MINIMUM_PEPPER_BYTES) {
            "The ingest-token pepper must contain at least $MINIMUM_PEPPER_BYTES bytes."
        }
    }

    fun digest(secret: String): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(secret.toByteArray(StandardCharsets.US_ASCII))
        }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val MINIMUM_PEPPER_BYTES = 32
    }
}

internal class SecureIngestAuthorizer(
    private val lookup: IngestCredentialLookup,
    private val digester: HmacTokenDigester,
    private val clock: Clock,
) : IngestAuthorizer {
    override fun isAuthorized(projectId: UUID, token: String): Boolean {
        val parsed = token.parseIngestToken() ?: return false
        val credential = lookup.find(projectId, parsed.credentialId) ?: return false
        val now = clock.instant()
        if (credential.revokedAt != null || credential.expiresAt?.isAfter(now) == false) return false
        return MessageDigest.isEqual(credential.tokenDigest, digester.digest(parsed.secret))
    }
}

private data class ParsedIngestToken(
    val credentialId: UUID,
    val secret: String,
)

private fun String.parseIngestToken(): ParsedIngestToken? {
    if (!startsWith(TOKEN_PREFIX)) return null
    val separator = indexOf('.', TOKEN_PREFIX.length)
    if (separator < 0 || separator == lastIndex) return null
    val credentialId = runCatching { UUID.fromString(substring(TOKEN_PREFIX.length, separator)) }.getOrNull()
        ?: return null
    val secret = substring(separator + 1)
    val secretBytes = runCatching { Base64.getUrlDecoder().decode(secret) }.getOrNull() ?: return null
    if (secretBytes.size < MINIMUM_TOKEN_SECRET_BYTES) return null
    return ParsedIngestToken(credentialId, secret)
}

private const val TOKEN_PREFIX = "rt_ingest_"
private const val MINIMUM_TOKEN_SECRET_BYTES = 32
