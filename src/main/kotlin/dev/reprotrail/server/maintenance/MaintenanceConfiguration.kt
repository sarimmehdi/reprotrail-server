package dev.reprotrail.server.maintenance

import dev.reprotrail.server.reconciliation.TraceReconciliationRunner
import dev.reprotrail.server.retention.TraceRetentionRunner
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@ConfigurationProperties("reprotrail.maintenance")
internal data class MaintenanceProperties(
    val retention: Duration = Duration.ofDays(30),
    val retentionBatchSize: Int = 100,
    val reconciliationStaleAfter: Duration = Duration.ofMinutes(15),
    val reconciliationBatchSize: Int = 100,
)

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(MaintenanceProperties::class)
@ConditionalOnProperty(prefix = "reprotrail.maintenance", name = ["enabled"], havingValue = "true")
internal class MaintenanceConfiguration(
    private val retentionRunner: TraceRetentionRunner,
    private val reconciliationRunner: TraceReconciliationRunner,
    private val properties: MaintenanceProperties,
) {
    @Scheduled(fixedDelayString = "\${reprotrail.maintenance.fixed-delay:PT15M}")
    fun maintainTraces() {
        val reconciliation =
            reconciliationRunner.run(properties.reconciliationStaleAfter, properties.reconciliationBatchSize)
        val retention = retentionRunner.run(properties.retention, properties.retentionBatchSize)
        logger.info(
            "Trace maintenance completed: reconciled={}, reconciliationErrors={}, retained={}, retentionFailures={}",
            reconciliation.examined,
            reconciliation.errors,
            retention.deleted,
            retention.failed,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(MaintenanceConfiguration::class.java)
    }
}
