package dev.reprotrail.server.ingest

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

internal const val INGEST_AUTHORITY = "trace:ingest"

internal class IngestAuthenticationFilter(
    private val authorizer: IngestAuthorizer,
    private val maxTraceBytes: Long,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || request.ingestProjectId() == null

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val projectId = checkNotNull(request.ingestProjectId())
        val token = request.getHeader("Authorization").bearerToken()
        if (token == null || !authorizer.isAuthorized(projectId, token)) {
            response.jsonError(
                status = HttpServletResponse.SC_UNAUTHORIZED,
                code = "unauthorized",
                message = "Valid ingest credentials are required.",
            )
            return
        }

        val contentLength = request.contentLengthLong
        if (contentLength < 0) {
            response.jsonError(
                status = HttpServletResponse.SC_LENGTH_REQUIRED,
                code = "content_length_required",
                message = "Content-Length is required for trace ingestion.",
            )
            return
        }
        if (contentLength > maxTraceBytes) {
            response.jsonError(
                status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                code = "trace_too_large",
                message = "Trace content exceeds the configured size limit.",
            )
            return
        }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                "project:$projectId",
                null,
                listOf(SimpleGrantedAuthority(INGEST_AUTHORITY)),
            )
        SecurityContextHolder.setContext(context)
        filterChain.doFilter(request, response)
    }
}

private fun HttpServletRequest.ingestProjectId(): UUID? {
    val path = requestURI.removePrefix(contextPath).split('/')
    if (path.size != 5 || path[1] != "v1" || path[2] != "projects" || path[4] != "traces") return null
    return runCatching { UUID.fromString(path[3]) }.getOrNull()
}

private fun String?.bearerToken(): String? {
    if (this == null || !startsWith("Bearer ", ignoreCase = true)) return null
    return substring(7).trim().takeIf(String::isNotEmpty)
}

private fun HttpServletResponse.jsonError(
    status: Int,
    code: String,
    message: String,
) {
    this.status = status
    contentType = "application/json"
    characterEncoding = Charsets.UTF_8.name()
    writer.write("{\"code\":\"$code\",\"message\":\"$message\"}")
}
