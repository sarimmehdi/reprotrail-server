package dev.reprotrail.server.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

class TraceContractValidatorTest {
    private val validator = TraceContractValidator()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "complete-session.json",
            "minimal-tap.json",
            "production-consented.json",
        ],
    )
    fun `published valid fixtures are accepted`(fixtureName: String) {
        val result = validator.validate(fixture("valid/$fixtureName"))

        val valid = assertInstanceOf(TraceValidationResult.Valid::class.java, result)
        assertEquals("1.0.0-alpha.1", valid.metadata.schemaVersion)
        assertTrue(valid.metadata.actionCount > 0)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "coordinate-out-of-range.json",
            "empty-actions.json",
            "production-without-consent.json",
            "raw-text-input.json",
            "unclassified-text-selector.json",
            "unknown-root-property.json",
            "unsupported-version.json",
        ],
    )
    fun `published schema-invalid fixtures are rejected`(fixtureName: String) {
        val result = validator.validate(fixture("invalid/schema/$fixtureName"))

        val invalid = assertInstanceOf(TraceValidationResult.Invalid::class.java, result)
        assertTrue(invalid.issues.isNotEmpty())
        assertTrue(invalid.issues.all { it.phase == TraceIssuePhase.SCHEMA })
    }

    @Test
    fun `published semantic-invalid fixture retains its rule identity`() {
        val result = validator.validate(fixture("invalid/semantic/non-contiguous-sequence.json"))

        val invalid = assertInstanceOf(TraceValidationResult.Invalid::class.java, result)
        assertEquals(listOf("actions.sequence.contiguous"), invalid.issues.map(TraceIssue::rule))
    }

    @Test
    fun `malformed JSON is rejected without exposing input`() {
        val result = validator.validate("{not-json")

        val invalid = assertInstanceOf(TraceValidationResult.Invalid::class.java, result)
        assertEquals(TraceIssuePhase.PARSE, invalid.issues.single().phase)
        assertTrue(invalid.issues.single().message.isNotBlank())
    }

    private fun fixture(relativePath: String): String =
        checkNotNull(javaClass.getResource("/fixtures/v1alpha1/$relativePath")).readText()
}
