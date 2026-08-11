package dev.reprotrail.server.retention

import dev.reprotrail.server.access.TraceDeleter
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
internal class RetentionConfiguration {
    @Bean
    fun traceRetentionRunner(
        catalog: TraceRetentionCatalog,
        deleter: TraceDeleter,
        clock: Clock,
    ): TraceRetentionRunner = RetainTraces(catalog, deleter, clock)
}
