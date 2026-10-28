package io.vulscan.kit.worker

import io.vulscan.kit.net.ProbeRequest
import io.vulscan.kit.net.ProbeResponse
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Обёртка над отправкой запросов, применяющая лимиты задания: бюджет запросов, скорость, параллелизм,
 * срок и отмену. Каждый вызов [execute] учитывается в бюджете, даже если запрос завершился ошибкой.
 */
class BudgetedHttp(
    private val delegate: (ProbeRequest) -> ProbeResponse,
    private val rateLimiter: RateLimiter,
    private val maxRequests: Int,
    maxConcurrency: Int,
    private val deadline: Instant,
    private val isCancelled: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val sent = AtomicInteger()
    private val concurrency = Semaphore(maxConcurrency.coerceAtLeast(1))

    val requestsSent: Int get() = sent.get()

    fun execute(request: ProbeRequest): ProbeResponse {
        checkAlive()
        reserveBudget()
        concurrency.acquire()
        try {
            rateLimiter.acquire()
            checkAlive()
            return delegate(request)
        } finally {
            concurrency.release()
        }
    }

    private fun checkAlive() {
        if (isCancelled()) throw CancelledException()
        if (!clock.instant().isBefore(deadline)) throw DeadlineExceededException()
    }

    private fun reserveBudget() {
        while (true) {
            val current = sent.get()
            if (current >= maxRequests) throw BudgetExhaustedException()
            if (sent.compareAndSet(current, current + 1)) return
        }
    }
}
