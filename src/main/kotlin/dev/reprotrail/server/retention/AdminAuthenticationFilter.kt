package dev.reprotrail.server.retention

import dev.reprotrail.server.security.AdminAuthorizer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

internal const val RETENTION_ADMIN_AUTHORITY = "retention:admin"

internal class AdminAuthenticationFilter(
    private val authorizer: AdminAuthorizer,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.adminProjectId() == null

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val projectId = checkNotNull(request.adminProjectId())
        val token = request.getHeader("Authorization").adminBearerToken()
        val identity = token?.let { authorizer.authorize(projectId, it) }
        if (identity == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write(
                "{\"code\":\"unauthorized\",\"message\":\"Valid administrator credentials are required.\"}",
            )
            return
        }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                identity,
                null,
                listOf(SimpleGrantedAuthority(RETENTION_ADMIN_AUTHORITY)),
            )
        SecurityContextHolder.setContext(context)
        filterChain.doFilter(request, response)
    }
}

private fun HttpServletRequest.adminProjectId(): UUID? {
    val path = requestURI.removePrefix(contextPath).split('/')
    if (path.size != 5 || path[1] != "v1" || path[2] != "projects" || path[4] != "retention-policy") {
        return null
    }
    if (method != "GET" && method != "PUT") return null
    return runCatching { UUID.fromString(path[3]) }.getOrNull()
}

private fun String?.adminBearerToken(): String? {
    if (this == null || !startsWith("Bearer ", ignoreCase = true)) return null
    return substring(7).trim().takeIf(String::isNotEmpty)
}
