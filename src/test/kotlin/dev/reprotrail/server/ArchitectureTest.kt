package dev.reprotrail.server

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test

class ArchitectureTest {
    private val productionClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("dev.reprotrail.server")

    @Test
    fun `top-level packages remain free of dependency cycles`() {
        slices()
            .matching("dev.reprotrail.server.(*)..")
            .should().beFreeOfCycles()
            .check(productionClasses)
    }

    @Test
    fun `domain and security code do not depend on infrastructure adapters`() {
        noClasses()
            .that().resideInAnyPackage(
                "dev.reprotrail.server.contract..",
                "dev.reprotrail.server.ingest..",
                "dev.reprotrail.server.security..",
            ).should().dependOnClassesThat().resideInAnyPackage(
                "dev.reprotrail.server.persistence..",
                "dev.reprotrail.server.storage..",
            ).check(productionClasses)
    }

    @Test
    fun `trace contract rules remain framework independent`() {
        noClasses()
            .that().resideInAPackage("dev.reprotrail.server.contract..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "software.amazon.awssdk..",
                "java.sql..",
            ).check(productionClasses)
    }
}
