package dev.reprotrail.server.persistence

import java.time.ZoneOffset
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient

internal class JdbcTraceMetadataStore(
    private val jdbc: JdbcClient,
) : TraceMetadataStore {
    override fun reserve(record: TraceMetadataRecord): TraceReservation {
        val reservationId = UUID.randomUUID()
        return try {
            val row =
                jdbc.sql(
                    """
                    insert into traces (
                        project_id,
                        session_id,
                        idempotency_key,
                        content_sha256,
                        object_key,
                        schema_version,
                        started_at,
                        ended_at,
                        package_name,
                        capture_mode,
                        action_count,
                        storage_state,
                        reservation_id
                    ) values (
                        :projectId,
                        :sessionId,
                        :idempotencyKey,
                        :contentSha256,
                        :objectKey,
                        :schemaVersion,
                        :startedAt,
                        :endedAt,
                        :packageName,
                        :captureMode,
                        :actionCount,
                        'pending',
                        :reservationId
                    )
                    on conflict (project_id, idempotency_key) do update
                    set idempotency_key = excluded.idempotency_key
                    returning content_sha256, storage_state, reservation_id = :reservationId as created
                    """.trimIndent(),
                ).param("projectId", record.projectId)
                    .param("sessionId", record.metadata.sessionId)
                    .param("idempotencyKey", record.idempotencyKey)
                    .param("contentSha256", record.contentSha256)
                    .param("objectKey", record.objectKey)
                    .param("schemaVersion", record.metadata.schemaVersion)
                    .param("startedAt", record.metadata.startedAt.atOffset(ZoneOffset.UTC))
                    .param("endedAt", record.metadata.endedAt?.atOffset(ZoneOffset.UTC))
                    .param("packageName", record.metadata.packageName)
                    .param("captureMode", record.metadata.captureMode)
                    .param("actionCount", record.metadata.actionCount)
                    .param("reservationId", reservationId)
                    .query { resultSet, _ ->
                        ReservationRow(
                            created = resultSet.getBoolean("created"),
                            contentSha256 = resultSet.getBytes("content_sha256"),
                            state = resultSet.getString("storage_state").toTraceStorageState(),
                        )
                    }.single()
            if (row.created) {
                TraceReservation.Created
            } else {
                TraceReservation.Existing(row.contentSha256, row.state)
            }
        } catch (_: DataIntegrityViolationException) {
            TraceReservation.Conflict
        }
    }

    override fun markAvailable(projectId: UUID, sessionId: UUID) {
        updateState(projectId, sessionId, TraceStorageState.Available)
    }

    override fun markFailed(projectId: UUID, sessionId: UUID) {
        updateState(projectId, sessionId, TraceStorageState.Failed)
    }

    private fun updateState(projectId: UUID, sessionId: UUID, state: TraceStorageState) {
        val updated =
            jdbc.sql(
                """
                update traces
                set storage_state = :state, updated_at = current_timestamp
                where project_id = :projectId and session_id = :sessionId
                """.trimIndent(),
            ).param("state", state.databaseValue)
                .param("projectId", projectId)
                .param("sessionId", sessionId)
                .update()
        check(updated == 1) { "Trace metadata reservation is missing." }
    }

    private data class ReservationRow(
        val created: Boolean,
        val contentSha256: ByteArray,
        val state: TraceStorageState,
    )
}

private val TraceStorageState.databaseValue: String
    get() = name.lowercase()

private fun String.toTraceStorageState(): TraceStorageState =
    TraceStorageState.entries.single { it.databaseValue == this }
