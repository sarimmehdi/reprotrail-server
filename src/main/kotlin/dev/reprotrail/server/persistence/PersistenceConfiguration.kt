package dev.reprotrail.server.persistence

import dev.reprotrail.server.ingest.TraceRepository
import dev.reprotrail.server.access.TraceCatalog
import dev.reprotrail.server.access.TraceArtifactCatalog
import dev.reprotrail.server.access.TraceAuditLog
import dev.reprotrail.server.security.DeveloperCredentialLookup
import dev.reprotrail.server.security.HmacTokenDigester
import dev.reprotrail.server.security.IngestCredentialLookup
import dev.reprotrail.server.security.SecureDeveloperAuthorizer
import dev.reprotrail.server.security.SecureIngestAuthorizer
import java.time.Clock
import java.util.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient

@Configuration(proxyBeanMethods = false)
@Profile("!test")
internal class PersistenceConfiguration {
    @Bean
    fun applicationClock(): Clock = Clock.systemUTC()

    @Bean
    fun ingestCredentialLookup(jdbc: JdbcClient): IngestCredentialLookup = JdbcIngestCredentialLookup(jdbc)

    @Bean
    fun developerCredentialLookup(jdbc: JdbcClient): DeveloperCredentialLookup = JdbcDeveloperCredentialLookup(jdbc)

    @Bean
    fun traceMetadataStore(jdbc: JdbcClient): TraceMetadataStore = JdbcTraceMetadataStore(jdbc)

    @Bean
    fun jdbcTraceCatalog(jdbc: JdbcClient): JdbcTraceCatalog = JdbcTraceCatalog(jdbc)

    @Bean
    fun traceCatalog(catalog: JdbcTraceCatalog): TraceCatalog = catalog

    @Bean
    fun traceArtifactCatalog(catalog: JdbcTraceCatalog): TraceArtifactCatalog = catalog

    @Bean
    fun traceAuditLog(jdbc: JdbcClient): TraceAuditLog = JdbcTraceAuditLog(jdbc)

    @Bean
    fun traceRepository(
        metadataStore: TraceMetadataStore,
        contentStore: TraceContentStore,
    ): TraceRepository = PersistentTraceRepository(metadataStore, contentStore)

    @Bean
    fun hmacTokenDigester(
        @Value("\${reprotrail.security.token-pepper-base64}") encodedPepper: String,
    ): HmacTokenDigester {
        val pepper = runCatching { Base64.getDecoder().decode(encodedPepper) }
            .getOrElse { throw IllegalArgumentException("The ingest-token pepper must be valid Base64.") }
        return HmacTokenDigester(pepper)
    }

    @Bean
    fun secureIngestAuthorizer(
        lookup: IngestCredentialLookup,
        digester: HmacTokenDigester,
        clock: Clock,
    ): SecureIngestAuthorizer = SecureIngestAuthorizer(lookup, digester, clock)

    @Bean
    fun secureDeveloperAuthorizer(
        lookup: DeveloperCredentialLookup,
        digester: HmacTokenDigester,
        clock: Clock,
    ): SecureDeveloperAuthorizer = SecureDeveloperAuthorizer(lookup, digester, clock)
}
