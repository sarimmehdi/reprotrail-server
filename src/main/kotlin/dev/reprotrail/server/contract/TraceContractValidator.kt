package dev.reprotrail.server.contract

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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
        val semanticIssues = root.semanticIssues()
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

private fun JsonObject.semanticIssues(): List<TraceIssue> = buildList {
    validateSession(this)
    validateActions(this)
    validatePrivacy(this)
}

private fun JsonObject.validateSession(issues: MutableList<TraceIssue>) {
    val session = getValue("session").jsonObject
    val startedAt = Instant.parse(session.getValue("startedAt").jsonPrimitive.content)
    val endedAt = session["endedAt"]?.jsonPrimitive?.content?.let(Instant::parse)
    if (endedAt != null && endedAt < startedAt) {
        issues += semanticIssue(
            path = "/session/endedAt",
            rule = "session.time.order",
            message = "The session end cannot precede the session start.",
        )
    }

    val lastOffset = getValue("actions").jsonArray.last().jsonObject.getValue("offsetMs").jsonPrimitive.long
    val duration = session["durationMs"]?.jsonPrimitive?.long
    if (duration != null && duration < lastOffset) {
        issues += semanticIssue(
            path = "/session/durationMs",
            rule = "session.duration.covers_actions",
            message = "The session duration must include the final action offset.",
        )
    }
}

private fun JsonObject.validateActions(issues: MutableList<TraceIssue>) {
    val actionIds = mutableSetOf<String>()
    var previousOffset = -1L

    getValue("actions").jsonArray.forEachIndexed { index, actionElement ->
        val action = actionElement.jsonObject
        val sequence = action.getValue("sequence").jsonPrimitive.int
        if (sequence != index) {
            issues += semanticIssue(
                path = "/actions/$index/sequence",
                rule = "actions.sequence.contiguous",
                message = "Expected sequence $index but found $sequence.",
            )
        }

        val offset = action.getValue("offsetMs").jsonPrimitive.long
        if (offset < previousOffset) {
            issues += semanticIssue(
                path = "/actions/$index/offsetMs",
                rule = "actions.offset.non_decreasing",
                message = "Action offsets must not move backwards.",
            )
        }
        previousOffset = offset

        val actionId = action.getValue("id").jsonPrimitive.content
        if (!actionIds.add(actionId)) {
            issues += semanticIssue(
                path = "/actions/$index/id",
                rule = "actions.id.unique",
                message = "Action identifiers must be unique within a session.",
            )
        }

        if (action.getValue("type").jsonPrimitive.content == "swipe" && action.isZeroDistanceSwipe()) {
            issues += semanticIssue(
                path = "/actions/$index/end",
                rule = "swipe.distance.non_zero",
                message = "A swipe must end at a different normalized position.",
            )
        }

        action["target"]?.jsonObject?.validateTarget(
            actionIndex = index,
            selectorTextPolicy = getValue("privacy").jsonObject.getValue("selectorText").jsonPrimitive.content,
            issues = issues,
        )
    }
}

private fun JsonObject.isZeroDistanceSwipe(): Boolean {
    val start = getValue("start").jsonObject
    val end = getValue("end").jsonObject
    return start.coordinate("x") == end.coordinate("x") && start.coordinate("y") == end.coordinate("y")
}

private fun JsonObject.validateTarget(
    actionIndex: Int,
    selectorTextPolicy: String,
    issues: MutableList<TraceIssue>,
) {
    this["bounds"]?.jsonObject?.let { bounds ->
        if (bounds.coordinate("left") >= bounds.coordinate("right") ||
            bounds.coordinate("top") >= bounds.coordinate("bottom")
        ) {
            issues += semanticIssue(
                path = "/actions/$actionIndex/target/bounds",
                rule = "target.bounds.ordered",
                message = "Target bounds must have positive width and height.",
            )
        }
    }

    val selectorIdentities = mutableSetOf<String>()
    getValue("selectors").jsonArray.forEachIndexed { selectorIndex, selectorElement ->
        val selector = selectorElement.jsonObject
        if (!selectorIdentities.add(selector.identity())) {
            issues += semanticIssue(
                path = "/actions/$actionIndex/target/selectors/$selectorIndex",
                rule = "target.selectors.unique",
                message = "A target must not repeat an equivalent selector.",
            )
        }

        val type = selector.getValue("type").jsonPrimitive.content
        if ((type == "text" || type == "contentDescription") && selectorTextPolicy != "allowlisted") {
            issues += semanticIssue(
                path = "/actions/$actionIndex/target/selectors/$selectorIndex",
                rule = "privacy.selector_text.allowlisted",
                message = "Visible selector text requires the allowlisted selector policy.",
            )
        }
    }
}

private fun JsonObject.identity(): String {
    val type = getValue("type").jsonPrimitive.content
    return if (type == "coordinate") {
        "$type:${getValue("reference").jsonPrimitive.content}:${coordinate("x")}:${coordinate("y")}"
    } else {
        "$type:${getValue("value").jsonPrimitive.content}:${this["match"].contentOrEmpty()}"
    }
}

private fun JsonObject.validatePrivacy(issues: MutableList<TraceIssue>) {
    val privacy = getValue("privacy").jsonObject
    if (privacy.getValue("captureMode").jsonPrimitive.content != "production") return

    val capturedAt = Instant.parse(
        privacy.getValue("consent").jsonObject.getValue("capturedAt").jsonPrimitive.content,
    )
    val startedAt = Instant.parse(
        getValue("session").jsonObject.getValue("startedAt").jsonPrimitive.content,
    )
    if (capturedAt > startedAt) {
        issues += semanticIssue(
            path = "/privacy/consent/capturedAt",
            rule = "privacy.consent.precedes_capture",
            message = "Production consent must be recorded before capture begins.",
        )
    }
}

private fun semanticIssue(
    path: String,
    rule: String,
    message: String,
): TraceIssue = TraceIssue(path, TraceIssuePhase.SEMANTIC, rule, message)

private fun JsonObject.coordinate(name: String): Double = getValue(name).jsonPrimitive.double

private fun JsonElement?.contentOrEmpty(): String = this?.jsonPrimitive?.contentOrNull.orEmpty()

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
