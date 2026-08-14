package dev.reprotrail.server

import dev.reprotrail.server.access.DeveloperAuthenticationFilter
import dev.reprotrail.server.access.TRACE_DELETE_AUTHORITY
import dev.reprotrail.server.access.TRACE_READ_AUTHORITY
import dev.reprotrail.server.access.REPLAY_CREATE_AUTHORITY
import dev.reprotrail.server.access.REPLAY_READ_AUTHORITY
import dev.reprotrail.server.ingest.INGEST_AUTHORITY
import dev.reprotrail.server.ingest.IngestAuthenticationFilter
import dev.reprotrail.server.ingest.IngestAuthorizer
import dev.reprotrail.server.security.DeveloperAuthorizer
import dev.reprotrail.server.security.AdminAuthorizer
import dev.reprotrail.server.retention.AdminAuthenticationFilter
import dev.reprotrail.server.retention.RETENTION_ADMIN_AUTHORITY
import dev.reprotrail.server.security.WorkerAuthorizer
import dev.reprotrail.server.replay.REPLAY_WORK_AUTHORITY
import dev.reprotrail.server.replay.WorkerAuthenticationFilter
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.config.annotation.web.builders.HttpSecurity

@Configuration(proxyBeanMethods = false)
internal class SecurityConfiguration {
    @Bean
    fun ingestAuthenticationFilter(
        authorizer: IngestAuthorizer,
        @Value("\${reprotrail.ingest.max-trace-bytes:1048576}") maxTraceBytes: Long,
    ): IngestAuthenticationFilter = IngestAuthenticationFilter(authorizer, maxTraceBytes)

    @Bean
    fun developerAuthenticationFilter(authorizer: DeveloperAuthorizer): DeveloperAuthenticationFilter =
        DeveloperAuthenticationFilter(authorizer)

    @Bean
    fun workerAuthenticationFilter(authorizer: WorkerAuthorizer): WorkerAuthenticationFilter =
        WorkerAuthenticationFilter(authorizer)

    @Bean
    fun adminAuthenticationFilter(authorizer: AdminAuthorizer): AdminAuthenticationFilter =
        AdminAuthenticationFilter(authorizer)

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        ingestAuthenticationFilter: IngestAuthenticationFilter,
        developerAuthenticationFilter: DeveloperAuthenticationFilter,
        workerAuthenticationFilter: WorkerAuthenticationFilter,
        adminAuthenticationFilter: AdminAuthenticationFilter,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.contentType = "application/json"
                    response.writer.write(
                        "{\"code\":\"unauthorized\",\"message\":\"Valid credentials are required.\"}",
                    )
                }
            }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/projects/*/traces").hasAuthority(INGEST_AUTHORITY)
                    .requestMatchers(HttpMethod.GET, "/v1/projects/*/traces", "/v1/projects/*/traces/**")
                    .hasAuthority(TRACE_READ_AUTHORITY)
                    .requestMatchers(HttpMethod.DELETE, "/v1/projects/*/traces/*")
                    .hasAuthority(TRACE_DELETE_AUTHORITY)
                    .requestMatchers(HttpMethod.POST, "/v1/projects/*/traces/*/replay-jobs")
                    .hasAuthority(REPLAY_CREATE_AUTHORITY)
                    .requestMatchers(HttpMethod.GET, "/v1/projects/*/replay-jobs/**")
                    .hasAuthority(REPLAY_READ_AUTHORITY)
                    .requestMatchers("/v1/projects/*/retention-policy")
                    .hasAuthority(RETENTION_ADMIN_AUTHORITY)
                    .requestMatchers("/internal/v1/projects/*/replay-jobs/**")
                    .hasAuthority(REPLAY_WORK_AUTHORITY)
                    .anyRequest().denyAll()
            }
            .addFilterBefore(ingestAuthenticationFilter, AnonymousAuthenticationFilter::class.java)
            .addFilterBefore(developerAuthenticationFilter, AnonymousAuthenticationFilter::class.java)
            .addFilterBefore(workerAuthenticationFilter, AnonymousAuthenticationFilter::class.java)
            .addFilterBefore(adminAuthenticationFilter, AnonymousAuthenticationFilter::class.java)
        return http.build()
    }
}
