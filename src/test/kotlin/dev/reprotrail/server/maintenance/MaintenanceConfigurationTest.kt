package dev.reprotrail.server.maintenance

import dev.reprotrail.server.reconciliation.TraceReconciliationReport
import dev.reprotrail.server.reconciliation.TraceReconciliationRunner
import dev.reprotrail.server.retention.TraceRetentionReport
import dev.reprotrail.server.retention.TraceRetentionRunner
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MaintenanceConfigurationTest {
    @Test
    fun `maintenance reconciles before applying retention with configured bounds`() {
        val reconciliation = RecordingReconciliationRunner()
        val retention = RecordingRetentionRunner()
        val properties =
            MaintenanceProperties(
                retention = Duration.ofDays(14),
                retentionBatchSize = 25,
                reconciliationStaleAfter = Duration.ofMinutes(20),
                reconciliationBatchSize = 50,
            )

        MaintenanceConfiguration(retention, reconciliation, properties).maintainTraces()

        assertEquals(Duration.ofMinutes(20) to 50, reconciliation.request)
        assertEquals(Duration.ofDays(14) to 25, retention.request)
    }

    private class RecordingReconciliationRunner : TraceReconciliationRunner {
        var request: Pair<Duration, Int>? = null

        override fun run(staleAfter: Duration, batchSize: Int): TraceReconciliationReport {
            request = staleAfter to batchSize
            return TraceReconciliationReport(0, 0, 0, 0, 0)
        }
    }

    private class RecordingRetentionRunner : TraceRetentionRunner {
        var request: Pair<Duration, Int>? = null

        override fun run(retainFor: Duration, batchSize: Int): TraceRetentionReport {
            request = retainFor to batchSize
            return TraceRetentionReport(0, 0, 0, 0)
        }
    }
}
