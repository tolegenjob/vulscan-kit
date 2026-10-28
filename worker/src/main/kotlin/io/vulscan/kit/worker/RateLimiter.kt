package io.vulscan.kit.worker

import java.util.concurrent.locks.LockSupport

/**
 * Равномерный ограничитель скорости: слоты выдаются не чаще одного за 1/rps секунды, без начального всплеска.
 * Потокобезопасен; [acquire] блокирует вызывающий поток до своего слота.
 */
class RateLimiter(
    requestsPerSecond: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val intervalNanos: Long
    private var nextFree: Long
    private val lock = Any()

    init {
        require(requestsPerSecond > 0) { "requestsPerSecond must be positive" }
        intervalNanos = 1_000_000_000L / requestsPerSecond
        nextFree = nanoTime()
    }

    fun acquire() {
        val slot: Long
        synchronized(lock) {
            slot = maxOf(nanoTime(), nextFree)
            nextFree = slot + intervalNanos
        }
        while (true) {
            val remaining = slot - nanoTime()
            if (remaining <= 0) return
            LockSupport.parkNanos(remaining)
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                throw CancelledException()
            }
        }
    }
}
