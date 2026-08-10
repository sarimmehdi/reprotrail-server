package dev.reprotrail.server.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

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

    @TestFactory
    fun `semantic invariants retain their specification rule identities`(): List<DynamicTest> {
        val complete = fixture("valid/complete-session.json")
        val production = fixture("valid/production-consented.json")
        val firstActionId = "018f1f4e-8aef-7bb8-a846-e8c86a0d57af"
        val secondActionId = "018f1f4f-19a4-7930-94d6-e9cbbd936541"

        val cases =
            listOf(
                SemanticCase(
                    "session end ordering",
                    complete.replace("2026-08-05T10:15:42.500Z", "2026-08-05T10:15:29.000Z"),
                    "session.time.order",
                ),
                SemanticCase(
                    "session duration covers actions",
                    complete.replace("\"durationMs\": 12500", "\"durationMs\": 11999"),
                    "session.duration.covers_actions",
                ),
                SemanticCase(
                    "action offsets do not move backwards",
                    complete.replace("\"offsetMs\": 2500", "\"offsetMs\": 1200"),
                    "actions.offset.non_decreasing",
                ),
                SemanticCase(
                    "action identifiers are unique",
                    complete.replace(secondActionId, firstActionId),
                    "actions.id.unique",
                ),
                SemanticCase(
                    "swipes have non-zero distance",
                    complete.replaceFirst("\"y\": 0.2", "\"y\": 0.8"),
                    "swipe.distance.non_zero",
                ),
                SemanticCase(
                    "target bounds are ordered",
                    complete.replace("\"right\": 0.95", "\"right\": 0.6"),
                    "target.bounds.ordered",
                ),
                SemanticCase(
                    "target selectors are unique",
                    complete
                        .replaceFirst("\"type\": \"resourceId\"", "\"type\": \"replayId\"")
                        .replaceFirst(
                            "\"value\": \"dev.reprotrail.fixture:id/submit_order\"",
                            "\"value\": \"checkout.submit\"",
                        ),
                    "target.selectors.unique",
                ),
                SemanticCase(
                    "visible selector text requires an allowlist",
                    complete.replace("\"selectorText\": \"allowlisted\"", "\"selectorText\": \"disabled\""),
                    "privacy.selector_text.allowlisted",
                ),
                SemanticCase(
                    "production consent precedes capture",
                    production.replace("2026-08-05T10:15:00.000Z", "2026-08-05T10:15:31.000Z"),
                    "privacy.consent.precedes_capture",
                ),
            )

        return cases.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val result = validator.validate(case.input)

                val invalid = assertInstanceOf(TraceValidationResult.Invalid::class.java, result)
                assertTrue(
                    invalid.issues.any { it.rule == case.rule },
                    "Expected ${case.rule}, got ${invalid.issues.map(TraceIssue::rule)}",
                )
            }
        }
    }

    private fun fixture(relativePath: String): String =
        checkNotNull(javaClass.getResource("/fixtures/v1alpha1/$relativePath")).readText()

    private data class SemanticCase(
        val name: String,
        val input: String,
        val rule: String,
    )
}
