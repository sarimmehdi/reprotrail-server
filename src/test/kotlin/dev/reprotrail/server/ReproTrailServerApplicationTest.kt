package dev.reprotrail.server

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication

class ReproTrailServerApplicationTest {
    @Test
    fun `application exposes one Spring Boot entry point`() {
        assertNotNull(ReproTrailServerApplication::class.java.getAnnotation(SpringBootApplication::class.java))
    }
}
