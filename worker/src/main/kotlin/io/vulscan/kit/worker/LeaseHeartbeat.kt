package io.vulscan.kit.worker

import io.vulscan.kit.contracts.HeartbeatRequest
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Периодически продлевает lease и узнаёт об отмене. Работает в daemon-потоке. */
class LeaseHeartbeat(
    private val core: CoreControlClient,
    private val jobId: UUID,
    private val attempt: Int,
    private val interval: Duration,
    private val requestsSent: () -> Int,
    private val endpointsDone: () -> Int,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(LeaseHeartbeat::class.java)
    private val cancelFlag = AtomicBoolean(false)
    private val leaseLostFlag = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "lease-heartbeat-$jobId").apply { isDaemon = true }
    }

    val cancelled: Boolean get() = cancelFlag.get()
    val leaseLost: Boolean get() = leaseLostFlag.get()

    fun start() {
        scheduler.scheduleWithFixedDelay(::beat, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun beat() {
        try {
            when (val result = core.heartbeat(jobId, HeartbeatRequest(attempt, requestsSent(), endpointsDone()))) {
                is HeartbeatResult.Ok -> if (result.response.cancel) cancelFlag.set(true)
                HeartbeatResult.LeaseLost -> {
                    leaseLostFlag.set(true)
                    scheduler.shutdown()
                }
            }
        } catch (e: CoreUnavailableException) {
            // Временная недоступность Core: следующая попытка через интервал; lease истечёт сам, если Core не вернётся
            log.warn("Heartbeat failed for job {}: {}", jobId, e.message)
        } catch (e: RuntimeException) {
            log.warn("Heartbeat error for job {}: {}", jobId, e.javaClass.simpleName)
        }
    }

    override fun close() {
        scheduler.shutdownNow()
    }
}
