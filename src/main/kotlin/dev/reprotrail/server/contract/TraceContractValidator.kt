package dev.reprotrail.server.contract

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class TraceIssuePhase {
    PARSE,
    SCHEMA,
    SEMANTIC,
}

internal data class TraceIssue(
    val path: String,
    val phase: TraceIssuePhase,
    val rule: String,
    val message: String,
)

internal data class ValidatedTraceMetadata(
    val schemaVersion: String,
    val sessionId: UUID,
    val startedAt: Instant,
    val endedAt: Instant?,
    val packageName: String,
    val captureMode: String,
    val actionCount: Int,
)

internal sealed interface TraceValidationResult {
    data class Valid(val metadata: ValidatedTraceMetadata) : TraceValidationResult

    data class Invalid(val issues: List<TraceIssue>) : TraceValidationResult
}

internal class TraceContractValidator {
    private val json = Json
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(loadSchema(), InputFormat.JSON)

    fun validate(input: String): TraceValidationResult {
        val schemaErrors =
            runCatching {
                schema.validate(input, InputFormat.JSON) { context ->
                    context.executionConfig { config -> config.formatAssertionsEnabled(true) }
                }
            }.getOrElse {
                return TraceValidationResult.Invalid(
                    listOf(
                        TraceIssue(
                            path = "",
                            phase = TraceIssuePhase.PARSE,
                            rule = "parse.json",
                            message = "Trace body is not valid JSON.",
                        ),
                    ),
                )
            }

        if (schemaErrors.isNotEmpty()) {
            return TraceValidationResult.Invalid(
                schemaErrors.map { error ->
                    TraceIssue(
                        path = error.instanceLocation.toString().toJsonPointer(),
                        phase = TraceIssuePhase.SCHEMA,
                        rule = "schema.${error.keyword}",
                        message = error.message,
                    )
                },
            )
        }

        val root = json.parseToJsonElement(input).jsonObject
        val semanticIssues = root.sequenceIssues()
        if (semanticIssues.isNotEmpty()) {
            return TraceValidationResult.Invalid(semanticIssues)
        }

        return TraceValidationResult.Valid(root.toMetadata())
    }

    private fun loadSchema(): String =
        checkNotNull(javaClass.getResource("/reprotrail/schema/v1alpha1/reprotrail-trace.schema.json")) {
            "Bundled ReproTrail schema is missing."
        }.readText()
}

private fun JsonObject.sequenceIssues(): List<TraceIssue> =
    getValue("actions").jsonArray.mapIndexedNotNull { index, actionElement ->
        val sequence = actionElement.jsonObject.getValue("sequence").jsonPrimitive.int
        if (sequence == index) {
            null
        } else {
            TraceIssue(
                path = "/actions/$index/sequence",
                phase = TraceIssuePhase.SEMANTIC,
                rule = "actions.sequence.contiguous",
                message = "Expected sequence $index but found $sequence.",
            )
        }
    }

private fun JsonObject.toMetadata(): ValidatedTraceMetadata {
    val session = getValue("session").jsonObject
    val actions = getValue("actions").jsonArray
    return ValidatedTraceMetadata(
        schemaVersion = getValue("schemaVersion").jsonPrimitive.content,
        sessionId = UUID.fromString(session.getValue("id").jsonPrimitive.content),
        startedAt = Instant.parse(session.getValue("startedAt").jsonPrimitive.content),
        endedAt = session["endedAt"]?.jsonPrimitive?.content?.let(Instant::parse),
        packageName = getValue("application").jsonObject.getValue("packageName").jsonPrimitive.content,
        captureMode = getValue("privacy").jsonObject.getValue("captureMode").jsonPrimitive.content,
        actionCount = actions.size,
    )
}

private fun String.toJsonPointer(): String {
    if (this == "$" || isEmpty()) return ""
    return removePrefix("$")
        .replace(Regex("\\['([^']+)'\\]")) { match -> "/${match.groupValues[1]}" }
        .replace(Regex("\\[(\\d+)]")) { match -> "/${match.groupValues[1]}" }
        .replace('.', '/')
}
