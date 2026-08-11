package dev.reprotrail.server.reconciliation

import dev.reprotrail.server.access.TraceArtifactDeleter
import dev.reprotrail.server.access.TraceDeletionCatalog
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
internal class ReconciliationConfiguration {
    @Bean
    fun traceReconciliationRunner(
        catalog: TraceReconciliationCatalog,
        inspector: TraceArtifactInspector,
        artifactDeleter: TraceArtifactDeleter,
        deletionCatalog: TraceDeletionCatalog,
        clock: Clock,
    ): TraceReconciliationRunner =
        ReconcileTraces(catalog, inspector, artifactDeleter, deletionCatalog, clock)
}
