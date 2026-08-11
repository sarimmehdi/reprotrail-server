package dev.reprotrail.server.ingest

import dev.reprotrail.server.contract.TraceContractValidator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
internal class IngestionConfiguration {
    @Bean
    fun traceContractValidator(): TraceContractValidator = TraceContractValidator()

    @Bean
    fun traceIngestor(
        authorizer: IngestAuthorizer,
        validator: TraceContractValidator,
        repository: TraceRepository,
    ): TraceIngestor = IngestTraceUseCase(authorizer, validator, repository)
}
