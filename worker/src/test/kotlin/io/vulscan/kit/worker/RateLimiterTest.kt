package io.vulscan.kit.worker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RateLimiterTest {

    @Test
    fun `at most rps plus one acquisitions complete within one second under contention`() {
        val limiter = RateLimiter(5)
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(20)
        val start = CountDownLatch(1)
        repeat(20) {
            pool.submit {
                start.await()
                limiter.acquire()
                done.incrementAndGet()
            }
        }
        start.countDown()
        Thread.sleep(1000)
        val completed = done.get()
        pool.shutdownNow()
        pool.awaitTermination(2, TimeUnit.SECONDS)
        assertThat(completed).isBetween(4, 6)
    }

    @Test
    fun `non positive rate is rejected`() {
        org.assertj.core.api.Assertions.assertThatThrownBy { RateLimiter(0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
