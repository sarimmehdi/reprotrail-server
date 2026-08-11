package dev.reprotrail.server.access

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
internal class AccessConfiguration {
    @Bean
    fun traceBrowser(catalog: TraceCatalog): TraceBrowser = BrowseTraces(catalog)

    @Bean
    fun traceDownloader(
        catalog: TraceArtifactCatalog,
        reader: TraceArtifactReader,
        auditLog: TraceAuditLog,
        clock: Clock,
    ): TraceDownloader = DownloadTrace(catalog, reader, auditLog, clock)
}
