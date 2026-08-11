package dev.reprotrail.server.replay

import dev.reprotrail.server.security.WorkerAuthorizer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

internal const val REPLAY_WORK_AUTHORITY = "replay:work"

internal class WorkerAuthenticationFilter(
    private val authorizer: WorkerAuthorizer,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.workerProjectId() == null

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val projectId = checkNotNull(request.workerProjectId())
        val token = request.getHeader("Authorization").workerBearerToken()
        val identity = token?.let { authorizer.authorize(projectId, it) }
        if (identity == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write("{\"code\":\"unauthorized\",\"message\":\"Valid worker credentials are required.\"}")
            return
        }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                identity,
                null,
                listOf(SimpleGrantedAuthority(REPLAY_WORK_AUTHORITY)),
            )
        SecurityContextHolder.setContext(context)
        filterChain.doFilter(request, response)
    }
}

private fun HttpServletRequest.workerProjectId(): UUID? {
    val path = requestURI.removePrefix(contextPath).split('/')
    if (path.size < 7 || path[1] != "internal" || path[2] != "v1" || path[3] != "projects" ||
        path[5] != "replay-jobs"
    ) {
        return null
    }
    return runCatching { UUID.fromString(path[4]) }.getOrNull()
}

private fun String?.workerBearerToken(): String? {
    if (this == null || !startsWith("Bearer ", ignoreCase = true)) return null
    return substring(7).trim().takeIf(String::isNotEmpty)
}
