package io.vulscan.kit.contracts

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

const val SCHEMA_VERSION = 1

/** Конверт Kafka-сообщения для записи (типизированный payload). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Envelope<T>(
    val schemaVersion: Int = SCHEMA_VERSION,
    val messageId: UUID = UUID.randomUUID(),
    val type: MessageType,
    val correlationId: UUID,
    val occurredAt: Instant = Instant.now(),
    val payload: T,
)

/** Конверт при чтении: payload остаётся деревом, тип определяется по [type]. */
data class RawEnvelope(
    val schemaVersion: Int,
    val messageId: UUID,
    val type: MessageType,
    val correlationId: UUID,
    val occurredAt: Instant,
    val payload: JsonNode,
)

/** Имена Kafka-топиков (версия топика входит в имя). */
object KafkaTopics {
    const val JOB_EVENTS = "scan.job-events.v1"
    const val DLQ = "scan.dlq.v1"

    fun jobsFor(module: ModuleType): String = "scan.jobs.${module.name.lowercase()}.v1"
}
