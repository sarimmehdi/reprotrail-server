package dev.reprotrail.server.access

import dev.reprotrail.server.security.DeveloperAuthorizer
import dev.reprotrail.server.security.DeveloperIdentity
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

internal const val TRACE_READ_AUTHORITY = "trace:read"
internal const val TRACE_DELETE_AUTHORITY = "trace:delete"

internal class DeveloperAuthenticationFilter(
    private val authorizer: DeveloperAuthorizer,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method !in SUPPORTED_METHODS || request.traceProjectId() == null

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val projectId = checkNotNull(request.traceProjectId())
        val token = request.getHeader("Authorization").bearerToken()
        val identity = token?.let { authorizer.authorize(projectId, it) }
        if (identity == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write(
                "{\"code\":\"unauthorized\",\"message\":\"Valid developer credentials are required.\"}",
            )
            return
        }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                identity,
                null,
                listOf(
                    SimpleGrantedAuthority(TRACE_READ_AUTHORITY),
                    SimpleGrantedAuthority(TRACE_DELETE_AUTHORITY),
                ),
            )
        SecurityContextHolder.setContext(context)
        filterChain.doFilter(request, response)
    }

    private companion object {
        val SUPPORTED_METHODS = setOf("GET", "DELETE")
    }
}

private fun HttpServletRequest.traceProjectId(): UUID? {
    val path = requestURI.removePrefix(contextPath).split('/')
    if (path.size !in 5..7 || path[1] != "v1" || path[2] != "projects" || path[4] != "traces") return null
    return runCatching { UUID.fromString(path[3]) }.getOrNull()
}

private fun String?.bearerToken(): String? {
    if (this == null || !startsWith("Bearer ", ignoreCase = true)) return null
    return substring(7).trim().takeIf(String::isNotEmpty)
}
