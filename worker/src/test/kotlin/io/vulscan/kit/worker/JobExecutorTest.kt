package io.vulscan.kit.worker

import io.vulscan.kit.contracts.ClaimResponse
import io.vulscan.kit.contracts.JobErrorCode
import io.vulscan.kit.contracts.ScanJobPayload
import io.vulscan.kit.net.ScopeDenial
import io.vulscan.kit.net.ScopeViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class JobExecutorTest {

    private val claim = ClaimResponse(Instant.now().plusSeconds(60), 1)
    private val publisher = FakePublisher()
    private val factory = HttpFactory { _, _ -> { Fixtures.response() } }

    private fun run(
        job: ScanJobPayload = Fixtures.job(),
        core: FakeCore = FakeCore(),
        body: (JobContext) -> Unit,
    ) {
        val engine = LambdaEngine(body = body)
        JobExecutor(engine, core, publisher, testProperties(), factory).execute(job, UUID.randomUUID(), claim)
    }

    @Test
    fun `normal run publishes findings and completion`() {
        run { ctx ->
            ctx.http.execute(Fixtures.request())
            ctx.stats.endpointTested()
            ctx.report(Fixtures.finding())
        }
        assertThat(publisher.findings).hasSize(1)
        assertThat(publisher.completed).hasSize(1)
        val stats = publisher.completed.single()
        assertThat(stats.findingsCount).isEqualTo(1)
        assertThat(stats.requestsSent).isEqualTo(1)
        assertThat(stats.endpointsTested).isEqualTo(1)
        assertThat(publisher.failed).isEmpty()
    }

    @Test
    fun `invalid finding is dropped and counted as inconclusive`() {
        run { ctx -> ctx.report(Fixtures.finding().copy(confidence = 100)) }
        assertThat(publisher.findings).isEmpty()
        assertThat(publisher.completed.single().inconclusive).isEqualTo(1)
    }

    @Test
    fun `engine exception becomes retryable internal error without a stack trace`() {
        run { throw IllegalStateException("secret detail") }
        val failure = publisher.failed.single()
        assertThat(failure.code).isEqualTo(JobErrorCode.INTERNAL_ERROR)
        assertThat(failure.retryable).isTrue()
        assertThat(failure.message).doesNotContain("secret detail").doesNotContain("\n")
        assertThat(publisher.completed).isEmpty()
    }

    @Test
    fun `exhausted budget completes with the flag`() {
        val job = Fixtures.job().let { it.copy(limits = it.limits.copy(maxRequests = 2)) }
        run(job) { ctx -> repeat(5) { ctx.http.execute(Fixtures.request()) } }
        val stats = publisher.completed.single()
        assertThat(stats.budgetExhausted).isTrue()
        assertThat(stats.requestsSent).isEqualTo(2)
    }

    @Test
    fun `scope violation fails the job without retry`() {
        run { throw ScopeViolationException(ScopeDenial.OUT_OF_SCOPE, "nope") }
        val failure = publisher.failed.single()
        assertThat(failure.code).isEqualTo(JobErrorCode.SCOPE_VIOLATION)
        assertThat(failure.retryable).isFalse()
    }

    @Test
    fun `passed deadline fails the job without retry`() {
        val job = Fixtures.job().copy(deadlineAt = Instant.now().minusSeconds(1))
        run(job) { ctx -> ctx.http.execute(Fixtures.request()) }
        val failure = publisher.failed.single()
        assertThat(failure.code).isEqualTo(JobErrorCode.DEADLINE_EXCEEDED)
        assertThat(failure.retryable).isFalse()
    }

    @Test
    fun `cancellation publishes nothing`() {
        val core = FakeCore(heartbeatResult = { HeartbeatResult.Ok(io.vulscan.kit.contracts.HeartbeatResponse(true, Instant.now().plusSeconds(60))) })
        run(core = core) { ctx ->
            while (!ctx.isCancelled()) Thread.sleep(20)
            ctx.http.execute(Fixtures.request())
        }
        assertThat(publisher.completed).isEmpty()
        assertThat(publisher.failed).isEmpty()
    }

    @Test
    fun `lost lease publishes nothing`() {
        val core = FakeCore(heartbeatResult = { HeartbeatResult.LeaseLost })
        run(core = core) { ctx ->
            while (!ctx.isCancelled()) Thread.sleep(20)
        }
        assertThat(publisher.completed).isEmpty()
        assertThat(publisher.failed).isEmpty()
    }
}
