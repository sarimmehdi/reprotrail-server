import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.reprotrail"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("software.amazon.awssdk:bom:2.48.2"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.networknt:json-schema-validator:3.0.6")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-minio")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

abstract class DownloadVerifiedContractAsset : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val bytes = URI(sourceUrl.get()).toURL().openStream().use { it.readBytes() }
        val actualSha256 =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        check(actualSha256 == expectedSha256.get()) {
            "ReproTrail contract checksum mismatch: expected ${expectedSha256.get()}, got $actualSha256"
        }

        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }
}

val contractVersion = "v1.0.0-alpha.1"
val contractBaseUrl = "https://raw.githubusercontent.com/sarimmehdi/reprotrail-spec/$contractVersion"
val mainContractResources = layout.buildDirectory.dir("generated/reprotrail-contract/main")
val testContractResources = layout.buildDirectory.dir("generated/reprotrail-contract/test")

val downloadContractSchema =
    tasks.register<DownloadVerifiedContractAsset>("downloadContractSchema") {
        sourceUrl.set("$contractBaseUrl/schema/v1alpha1/reprotrail-trace.schema.json")
        expectedSha256.set("316102dc64e30e4496565778e8ce0882ccf986fb5d7553c81a60d18e34ccd62f")
        destination.set(
            mainContractResources.map { resources ->
                resources.file("reprotrail/schema/v1alpha1/reprotrail-trace.schema.json")
            },
        )
    }

val contractTestAssets =
    mapOf(
        "compatibility/v1alpha1.json" to "2d78fd0e97e41ebb8dda365cccfdd7ed0ec2627e94d8e5095a35f7d01fbd6a27",
        "fixtures/v1alpha1/invalid/schema/coordinate-out-of-range.json" to
            "6b40b3de966f2bdeb53f4c5da8dff943d62803564e91dec334ce9c4fd29b0d62",
        "fixtures/v1alpha1/invalid/schema/empty-actions.json" to
            "ba0e4894e40f0c62c400f76f8193cd4926547e7a262bc2b7ec1ccd6e054f4302",
        "fixtures/v1alpha1/invalid/schema/production-without-consent.json" to
            "eb4227301e31d0bc8b74e04a0a467b5fdd230b8b1c3cd70efe47b7a4555da565",
        "fixtures/v1alpha1/invalid/schema/raw-text-input.json" to
            "c9ab931452c7af0ead8e3d606f32248733618209978dc92464730d4a14c31454",
        "fixtures/v1alpha1/invalid/schema/unclassified-text-selector.json" to
            "66863c54edd9f445201a71589cdd05b46040d25688e775018be411c293b98419",
        "fixtures/v1alpha1/invalid/schema/unknown-root-property.json" to
            "3639d0da9e8307ca7e0e0d592b89da6c0b151364a3078a5f7cf5bae45d93fab3",
        "fixtures/v1alpha1/invalid/schema/unsupported-version.json" to
            "e03c01fcdb56290c55d519641907bac91f5abc953f5fda9c8650566bd9210ac7",
        "fixtures/v1alpha1/invalid/semantic/non-contiguous-sequence.json" to
            "153301640545dfc758227bd16ec78b415f7ef07e6b03cfb9bf48e61def585cb9",
        "fixtures/v1alpha1/valid/complete-session.json" to
            "380a2509edd6f72a581d7b1edccb7770614a66a617cd4f6f0a754255992b1a7a",
        "fixtures/v1alpha1/valid/minimal-tap.json" to
            "13f0de5b6ae6e5cdbeb9c90297b75bb8cabbd7ce161f113390e502ea80a8f829",
        "fixtures/v1alpha1/valid/production-consented.json" to
            "bf03a39f727bec4dae436ec8739a398f9011993da6463d368df9dfa565a672c9",
    )

val downloadContractTestAssets =
    contractTestAssets.map { (path, checksum) ->
        tasks.register<DownloadVerifiedContractAsset>(
            "downloadContract" + path.split('/', '-', '.').joinToString("") { it.replaceFirstChar(Char::uppercase) },
        ) {
            sourceUrl.set("$contractBaseUrl/$path")
            expectedSha256.set(checksum)
            destination.set(testContractResources.map { resources -> resources.file(path) })
        }
    }

sourceSets.main {
    resources.srcDir(mainContractResources)
}

sourceSets.test {
    resources.srcDir(testContractResources)
}

tasks.processResources {
    dependsOn(downloadContractSchema)
}

tasks.processTestResources {
    dependsOn(downloadContractTestAssets)
}
