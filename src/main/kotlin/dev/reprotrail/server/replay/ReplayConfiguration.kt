package dev.reprotrail.server.replay

import dev.reprotrail.server.access.TraceArtifactCatalog
import dev.reprotrail.server.access.TraceArtifactReader
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
    ): ManageReplayLease = ManageReplayLease(leases, clock, leaseDuration)

    @Bean
    fun downloadReplayInput(
        leases: ReplayLeaseStore,
        traces: TraceArtifactCatalog,
        applications: ApplicationArtifactCatalog,
        reader: TraceArtifactReader,
        clock: Clock,
    ): DownloadReplayInput = DownloadReplayInput(leases, traces, applications, reader, clock)
}
