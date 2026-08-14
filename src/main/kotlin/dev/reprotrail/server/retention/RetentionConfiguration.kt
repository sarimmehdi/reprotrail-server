package dev.reprotrail.server.retention

import dev.reprotrail.server.access.TraceDeleter
import java.time.Clock
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
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

    @Bean
    fun retentionPolicyManager(
        store: RetentionPolicyStore,
        clock: Clock,
        @Value("\${reprotrail.maintenance.retention:P30D}") defaultRetainFor: Duration,
    ): RetentionPolicyManager = ManageRetentionPolicy(store, defaultRetainFor, clock)
}
