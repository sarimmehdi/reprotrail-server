package dev.reprotrail.server.retention

import dev.reprotrail.server.security.AdminIdentity
import java.security.Principal
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
internal class RetentionPolicyController(
    private val manager: RetentionPolicyManager,
) {
    @GetMapping("/v1/projects/{projectId}/retention-policy")
    fun find(@PathVariable projectId: UUID): ResponseEntity<RetentionPolicyResponse> =
        ResponseEntity.ok(manager.find(projectId).toResponse())

    @PutMapping("/v1/projects/{projectId}/retention-policy")
    fun update(
        @PathVariable projectId: UUID,
        @RequestBody body: UpdateRetentionPolicyBody,
        principal: Principal,
    ): ResponseEntity<Any> {
        val identity = (principal as? Authentication)?.principal as? AdminIdentity
            ?: return error(HttpStatus.UNAUTHORIZED, "unauthorized", "Valid administrator credentials are required.")
        if (body.retainForDays !in 1..3_650) return invalidPolicy()
        val policy =
            try {
                manager.update(projectId, body.retainForDays, identity.credentialId)
            } catch (_: IllegalArgumentException) {
                return invalidPolicy()
            }
        return ResponseEntity.ok(policy.toResponse())
    }

    private fun error(status: HttpStatus, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(RetentionPolicyErrorResponse(code, message))

    private fun invalidPolicy(): ResponseEntity<Any> =
        error(
            HttpStatus.BAD_REQUEST,
            "invalid_retention_policy",
            "Retention must be between 1 and 3650 days.",
        )
}

internal data class UpdateRetentionPolicyBody(val retainForDays: Int)

internal data class RetentionPolicyResponse(
    val projectId: UUID,
    val retainForDays: Int,
    val customized: Boolean,
    val updatedAt: Instant?,
    val updatedBy: UUID?,
)

internal data class RetentionPolicyErrorResponse(val code: String, val message: String)

private fun ProjectRetentionPolicy.toResponse() =
    RetentionPolicyResponse(projectId, retainForDays, customized, updatedAt, updatedBy)
