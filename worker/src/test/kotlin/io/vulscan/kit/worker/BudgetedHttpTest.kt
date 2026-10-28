package io.vulscan.kit.worker

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BudgetedHttpTest {

    private val future = Instant.now().plusSeconds(3600)

    private fun http(
        max: Int = 600,
        rps: Int = 100_000,
        deadline: Instant = future,
        cancelled: () -> Boolean = { false },
        clock: Clock = Clock.systemUTC(),
        calls: AtomicInteger = AtomicInteger(),
    ) = BudgetedHttp({ calls.incrementAndGet(); Fixtures.response() }, RateLimiter(rps), max, 4, deadline, cancelled, clock)

    @Test
    fun `request beyond the budget is refused`() {
        val calls = AtomicInteger()
        val http = http(max = 600, calls = calls)
        repeat(600) { http.execute(Fixtures.request()) }
        assertThatThrownBy { http.execute(Fixtures.request()) }.isInstanceOf(BudgetExhaustedException::class.java)
        assertThat(calls.get()).isEqualTo(600)
        assertThat(http.requestsSent).isEqualTo(600)
    }

    @Test
    fun `cancelled flag stops further requests`() {
        val flag = AtomicBoolean(false)
        val http = http(cancelled = flag::get)
        http.execute(Fixtures.request())
        flag.set(true)
        assertThatThrownBy { http.execute(Fixtures.request()) }.isInstanceOf(CancelledException::class.java)
    }

    @Test
    fun `passed deadline stops requests`() {
        val clock = Clock.fixed(future.plusSeconds(1), ZoneOffset.UTC)
        val http = http(clock = clock)
        assertThatThrownBy { http.execute(Fixtures.request()) }.isInstanceOf(DeadlineExceededException::class.java)
    }

    @Test
    fun `failed requests still count against the budget`() {
        val http = BudgetedHttp({ error("boom") }, RateLimiter(1000), 2, 1, future, { false })
        repeat(2) { assertThatThrownBy { http.execute(Fixtures.request()) }.isInstanceOf(IllegalStateException::class.java) }
        assertThatThrownBy { http.execute(Fixtures.request()) }.isInstanceOf(BudgetExhaustedException::class.java)
    }
}
