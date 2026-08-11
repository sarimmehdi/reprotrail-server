package dev.reprotrail.server

import dev.reprotrail.server.ingest.IngestAuthorizer
import dev.reprotrail.server.ingest.StoredTrace
import dev.reprotrail.server.ingest.TraceCreateResult
import dev.reprotrail.server.ingest.TraceIngestor
import dev.reprotrail.server.ingest.TraceRepository
import dev.reprotrail.server.security.DeveloperAuthorizer
import dev.reprotrail.server.security.DeveloperIdentity
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    ],
)
@Import(ReproTrailServerApplicationTest.InfrastructureTestConfiguration::class)
@ActiveProfiles("test")
class ReproTrailServerApplicationTest {
    @Autowired
    private lateinit var traceIngestor: TraceIngestor

    @Autowired
    private lateinit var securityFilterChain: SecurityFilterChain

    @Test
    fun `application composes the trace ingestion boundary`() {
        assertNotNull(traceIngestor)
        assertNotNull(securityFilterChain)
    }

    @TestConfiguration
    class InfrastructureTestConfiguration {
        @Bean
        internal fun ingestAuthorizer(): IngestAuthorizer = IngestAuthorizer { _, _ -> true }

        @Bean
        internal fun developerAuthorizer(): DeveloperAuthorizer =
            DeveloperAuthorizer { projectId, _ -> DeveloperIdentity(projectId, java.util.UUID.randomUUID()) }

        @Bean
        internal fun traceRepository(): TraceRepository =
            object : TraceRepository {
                override fun create(record: StoredTrace): TraceCreateResult = TraceCreateResult.Created
            }
    }
}
