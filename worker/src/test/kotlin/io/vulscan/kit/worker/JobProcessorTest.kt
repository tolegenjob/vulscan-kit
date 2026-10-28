package io.vulscan.kit.worker

import io.vulscan.kit.contracts.MessageType
import io.vulscan.kit.contracts.ModuleType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class JobProcessorTest {

    private fun processor(core: CoreControlClient = FakeCore(), module: ModuleType = ModuleType.XSS, hard: HardLimits = HardLimits()) =
        JobProcessor(module, hard, core, "w1", claimAttempts = 3, claimRetryDelayMs = 1)

    @Test
    fun `valid job is ready and limits are clamped`() {
        val job = Fixtures.job().let { it.copy(limits = it.limits.copy(maxRequests = 100_000, requestsPerSecond = 500)) }
        val result = processor().prepare(Fixtures.jobMessage(job))
        assertThat(result).isInstanceOf(Prepared.Ready::class.java)
        val ready = result as Prepared.Ready
        assertThat(ready.job.limits.maxRequests).isEqualTo(600)
        assertThat(ready.job.limits.requestsPerSecond).isEqualTo(10)
    }

    @Test
    fun `broken input goes to the dead letter queue`() {
        val p = processor()
        assertThat(p.prepare("not json")).isEqualTo(Prepared.DeadLetter("INVALID_JSON"))
        assertThat(p.prepare(null)).isEqualTo(Prepared.DeadLetter("EMPTY_MESSAGE"))
        assertThat(p.prepare(Fixtures.jobMessage(type = MessageType.JOB_COMPLETED))).isEqualTo(Prepared.DeadLetter("UNEXPECTED_MESSAGE_TYPE"))
    }

    @Test
    fun `message for another module is dead lettered`() {
        assertThat(processor(module = ModuleType.SQLI).prepare(Fixtures.jobMessage())).isEqualTo(Prepared.DeadLetter("WRONG_MODULE"))
    }

    @Test
    fun `job with passed deadline is skipped without claim`() {
        val core = FakeCore()
        val job = Fixtures.job().copy(deadlineAt = Instant.now().minusSeconds(5))
        assertThat(processor(core).prepare(Fixtures.jobMessage(job))).isEqualTo(Prepared.Skip("DEADLINE_PASSED"))
        assertThat(core.claims).isEmpty()
    }

    @Test
    fun `semantically invalid job is dead lettered`() {
        val job = Fixtures.job().copy(endpoints = emptyList())
        val result = processor().prepare(Fixtures.jobMessage(job))
        assertThat(result).isInstanceOf(Prepared.DeadLetter::class.java)
        assertThat((result as Prepared.DeadLetter).reason).startsWith("INVALID_JOB:")
    }

    @Test
    fun `claim retries on core outage and then fails`() {
        var calls = 0
        val core = FakeCore(claimResult = { calls++; throw CoreUnavailableException("down") })
        assertThatThrownBy { processor(core).claim(Fixtures.job()) }.isInstanceOf(CoreUnavailableException::class.java)
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `claim rejection is reported`() {
        val core = FakeCore(claimResult = { ClaimResult.Rejected(409) })
        assertThat(processor(core).claim(Fixtures.job())).isEqualTo(Claimed.Rejected(409))
    }

    @Test
    fun `claim succeeds after a transient failure`() {
        var calls = 0
        val granted = FakeCore().claimResult()
        val core = FakeCore(claimResult = { if (calls++ == 0) throw CoreUnavailableException("blip") else granted })
        assertThat(processor(core).claim(Fixtures.job())).isInstanceOf(Claimed.Granted::class.java)
    }
}
