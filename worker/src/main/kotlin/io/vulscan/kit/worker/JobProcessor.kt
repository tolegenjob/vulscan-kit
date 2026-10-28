package io.vulscan.kit.worker

import io.vulscan.kit.contracts.ClaimRequest
import io.vulscan.kit.contracts.ClaimResponse
import io.vulscan.kit.contracts.Json
import io.vulscan.kit.contracts.MessageType
import io.vulscan.kit.contracts.ModuleType
import io.vulscan.kit.contracts.SCHEMA_VERSION
import io.vulscan.kit.contracts.ScanJobPayload
import io.vulscan.kit.contracts.ScanJobPayloadValidator
import java.time.Clock
import java.util.UUID

sealed interface Prepared {
    /** Сообщение корректно и готово к claim. */
    data class Ready(val job: ScanJobPayload, val correlationId: UUID) : Prepared

    /** Подтвердить без запуска (например, срок уже прошёл). */
    data class Skip(val reason: String) : Prepared

    /** Некорректное сообщение: в DLQ без повторов. */
    data class DeadLetter(val reason: String) : Prepared
}

sealed interface Claimed {
    data class Granted(val response: ClaimResponse) : Claimed

    data class Rejected(val status: Int) : Claimed
}

/** Разбор и проверка входящих заданий, а также claim. Не знает про Kafka. */
class JobProcessor(
    private val moduleType: ModuleType,
    private val hardLimits: HardLimits,
    private val core: CoreControlClient,
    private val workerId: String,
    private val clock: Clock = Clock.systemUTC(),
    private val claimAttempts: Int = 3,
    private val claimRetryDelayMs: Long = 2000,
) {
    fun prepare(value: String?): Prepared {
        if (value.isNullOrBlank()) return Prepared.DeadLetter("EMPTY_MESSAGE")
        val raw = try {
            Json.readEnvelope(value)
        } catch (e: Exception) {
            return Prepared.DeadLetter("INVALID_JSON")
        }
        if (raw.schemaVersion != SCHEMA_VERSION) return Prepared.DeadLetter("UNSUPPORTED_SCHEMA_VERSION")
        if (raw.type != MessageType.SCAN_JOB) return Prepared.DeadLetter("UNEXPECTED_MESSAGE_TYPE")
        val job = try {
            Json.payloadAs(raw, ScanJobPayload::class.java)
        } catch (e: Exception) {
            return Prepared.DeadLetter("INVALID_PAYLOAD")
        }
        if (job.moduleType != moduleType) return Prepared.DeadLetter("WRONG_MODULE")
        val now = clock.instant()
        if (!job.deadlineAt.isAfter(now)) return Prepared.Skip("DEADLINE_PASSED")
        val violations = ScanJobPayloadValidator.validate(job, now)
        if (violations.isNotEmpty()) {
            return Prepared.DeadLetter("INVALID_JOB:" + violations.joinToString(",") { it.code.name })
        }
        return Prepared.Ready(job.copy(limits = hardLimits.clamp(job.limits)), raw.correlationId)
    }

    /** @throws CoreUnavailableException если Core недоступен после всех попыток. */
    fun claim(job: ScanJobPayload): Claimed {
        var last: CoreUnavailableException? = null
        repeat(claimAttempts) { i ->
            try {
                return when (val result = core.claim(job.jobId, ClaimRequest(job.attempt, workerId))) {
                    is ClaimResult.Granted -> Claimed.Granted(result.response)
                    is ClaimResult.Rejected -> Claimed.Rejected(result.status)
                }
            } catch (e: CoreUnavailableException) {
                last = e
                if (i < claimAttempts - 1) Thread.sleep(claimRetryDelayMs)
            }
        }
        throw last!!
    }
}
