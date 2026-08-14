package dev.reprotrail.server.replay

import dev.reprotrail.server.access.TraceArtifactCatalog
import dev.reprotrail.server.access.TraceArtifactReader
import dev.reprotrail.server.access.TraceAuditLog
import dev.reprotrail.server.reconciliation.TraceArtifactInspection
import dev.reprotrail.server.reconciliation.TraceArtifactInspector
import dev.reprotrail.server.access.TraceCatalog
import java.time.Clock
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
internal class ReplayConfiguration {
    @Bean
    fun createReplayJob(
        traces: TraceCatalog,
        applications: ApplicationArtifactCatalog,
        jobs: ReplayJobStore,
        clock: Clock,
    ): CreateReplayJob = CreateReplayJob(traces, applications, jobs, clock)

    @Bean
    fun leaseNextReplayJob(
        leases: ReplayLeaseStore,
        clock: Clock,
        @Value("\${reprotrail.replay.lease-duration:PT2M}") leaseDuration: Duration,
    ): LeaseNextReplayJob = LeaseNextReplayJob(leases, clock, leaseDuration)

    @Bean
    fun manageReplayLease(
        leases: ReplayLeaseStore,
        clock: Clock,
        @Value("\${reprotrail.replay.lease-duration:PT2M}") leaseDuration: Duration,
        artifactVerifier: ReplayArtifactVerifier,
    ): ManageReplayLease = ManageReplayLease(leases, clock, leaseDuration, artifactVerifier)

    @Bean
    fun downloadReplayInput(
        leases: ReplayLeaseStore,
        traces: TraceArtifactCatalog,
        applications: ApplicationArtifactCatalog,
        reader: TraceArtifactReader,
        clock: Clock,
    ): DownloadReplayInput = DownloadReplayInput(leases, traces, applications, reader, clock)

    @Bean
    fun replayArtifactVerifier(inspector: TraceArtifactInspector): ReplayArtifactVerifier =
        ReplayArtifactVerifier { projectId, jobId, artifact ->
            val digest = artifact.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            inspector.inspect(replayArtifactReference(projectId, jobId, artifact.name), digest) ==
                TraceArtifactInspection.Matching
        }

    @Bean
    fun uploadReplayArtifact(
        leases: ReplayLeaseStore,
        contentStore: ReplayArtifactContentStore,
        clock: Clock,
        @Value("\${reprotrail.replay.max-artifact-bytes:5242880}") maxArtifactBytes: Long,
    ): UploadReplayArtifact = UploadReplayArtifact(leases, contentStore, clock, maxArtifactBytes)

    @Bean
    fun downloadReplayArtifact(
        artifacts: ReplayArtifactCatalog,
        reader: TraceArtifactReader,
        auditLog: TraceAuditLog,
        clock: Clock,
    ): ReplayArtifactDownloader = DownloadReplayArtifact(artifacts, reader, auditLog, clock)
}
