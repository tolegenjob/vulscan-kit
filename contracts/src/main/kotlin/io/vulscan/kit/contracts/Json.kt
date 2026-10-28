package io.vulscan.kit.contracts

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Единственный сконфигурированный JSON-маппер для всех контрактов.
 * Неизвестные поля игнорируются (прямая совместимость), время пишется в ISO-8601.
 */
object Json {
    val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()

    fun write(value: Any): String = mapper.writeValueAsString(value)

    fun <T> read(json: String, type: Class<T>): T = mapper.readValue(json, type)

    /** Читает конверт; payload остаётся деревом. */
    fun readEnvelope(json: String): RawEnvelope = read(json, RawEnvelope::class.java)

    /** Преобразует payload конверта в нужный тип. */
    fun <T> payloadAs(raw: RawEnvelope, type: Class<T>): T = mapper.treeToValue(raw.payload, type)
}
