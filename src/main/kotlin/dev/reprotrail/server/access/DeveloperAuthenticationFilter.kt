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
internal const val REPLAY_CREATE_AUTHORITY = "replay:create"
internal const val REPLAY_READ_AUTHORITY = "replay:read"

internal class DeveloperAuthenticationFilter(
    private val authorizer: DeveloperAuthorizer,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.developerProjectId() == null

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val projectId = checkNotNull(request.developerProjectId())
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
                    SimpleGrantedAuthority(REPLAY_CREATE_AUTHORITY),
                    SimpleGrantedAuthority(REPLAY_READ_AUTHORITY),
                ),
            )
        SecurityContextHolder.setContext(context)
        filterChain.doFilter(request, response)
    }

}

private fun HttpServletRequest.developerProjectId(): UUID? {
    val path = requestURI.removePrefix(contextPath).split('/')
    if (path.size < 5 || path[1] != "v1" || path[2] != "projects") return null
    val supported =
        when (method) {
            "GET" -> path[4] == "traces" || path[4] == "replay-jobs"
            "DELETE" -> path[4] == "traces"
            "POST" -> path.size == 7 && path[4] == "traces" && path[6] == "replay-jobs"
            else -> false
        }
    if (!supported) return null
    return runCatching { UUID.fromString(path[3]) }.getOrNull()
}

private fun String?.bearerToken(): String? {
    if (this == null || !startsWith("Bearer ", ignoreCase = true)) return null
    return substring(7).trim().takeIf(String::isNotEmpty)
}
