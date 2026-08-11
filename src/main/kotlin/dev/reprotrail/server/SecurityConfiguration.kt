package dev.reprotrail.server

import dev.reprotrail.server.ingest.INGEST_AUTHORITY
import dev.reprotrail.server.ingest.IngestAuthenticationFilter
import dev.reprotrail.server.ingest.IngestAuthorizer
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
    fun securityFilterChain(
        http: HttpSecurity,
        ingestAuthenticationFilter: IngestAuthenticationFilter,
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
                    .anyRequest().denyAll()
            }
            .addFilterBefore(ingestAuthenticationFilter, AnonymousAuthenticationFilter::class.java)
        return http.build()
    }
}
