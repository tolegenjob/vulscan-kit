package io.vulscan.kit.worker

import io.vulscan.kit.contracts.ScanLimits
import java.util.UUID

/** Жёсткие верхние границы лимитов; значения из задания усекаются до них. */
data class HardLimits(
    val maxRequests: Int = 600,
    val requestsPerSecond: Int = 10,
    val maxConcurrency: Int = 4,
    val httpTimeoutMs: Int = 10_000,
    val maxResponseBytes: Int = 1_048_576,
    val maxDurationSec: Int = 1_800,
) {
    fun clamp(limits: ScanLimits) = ScanLimits(
        maxRequests = minOf(limits.maxRequests, maxRequests),
        requestsPerSecond = minOf(limits.requestsPerSecond, requestsPerSecond),
        maxConcurrency = minOf(limits.maxConcurrency, maxConcurrency),
        httpTimeoutMs = minOf(limits.httpTimeoutMs, httpTimeoutMs),
        maxResponseBytes = minOf(limits.maxResponseBytes, maxResponseBytes),
        maxDurationSec = minOf(limits.maxDurationSec, maxDurationSec),
    )
}

/** Настройки рантайма воркера (в модулях привязываются к префиксу `worker`). */
data class WorkerProperties(
    val coreBaseUrl: String,
    val serviceToken: String,
    val bootstrapServers: String,
    val consumerGroup: String,
    val workerId: String = "worker-" + UUID.randomUUID().toString().take(8),
    val maxConcurrentJobs: Int = 2,
    val hardLimits: HardLimits = HardLimits(),
    /** Диапазоны loopback/private/CGNAT, разрешённые для целей (например, сеть docker compose). */
    val allowedPrivateCidrs: List<String> = emptyList(),
) {
    init {
        require(maxConcurrentJobs > 0) { "maxConcurrentJobs must be positive" }
    }

    override fun toString(): String =
        "WorkerProperties(coreBaseUrl=$coreBaseUrl, serviceToken=[REDACTED], bootstrapServers=$bootstrapServers, " +
            "consumerGroup=$consumerGroup, workerId=$workerId, maxConcurrentJobs=$maxConcurrentJobs, hardLimits=$hardLimits, " +
            "allowedPrivateCidrs=$allowedPrivateCidrs)"
}
