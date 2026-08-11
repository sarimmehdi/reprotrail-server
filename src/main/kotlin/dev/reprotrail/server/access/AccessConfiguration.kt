package dev.reprotrail.server.access

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
internal class AccessConfiguration {
    @Bean
    fun traceBrowser(catalog: TraceCatalog): TraceBrowser = BrowseTraces(catalog)
}
