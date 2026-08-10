package dev.reprotrail.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Starts the authenticated ReproTrail ingestion service. */
@SpringBootApplication
class ReproTrailServerApplication

/** Runs ReproTrail Server with externalized infrastructure configuration. */
fun main(args: Array<String>) {
    runApplication<ReproTrailServerApplication>(*args)
}
