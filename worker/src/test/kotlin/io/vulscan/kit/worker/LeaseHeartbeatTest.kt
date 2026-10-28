package io.vulscan.kit.worker

import io.vulscan.kit.contracts.HeartbeatResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class LeaseHeartbeatTest {

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun `cancel in the response sets the flag`() {
        val core = FakeCore(heartbeatResult = { HeartbeatResult.Ok(HeartbeatResponse(true, Instant.now().plusSeconds(60))) })
        LeaseHeartbeat(core, UUID.randomUUID(), 1, Duration.ofMillis(50), { 7 }, { 1 }).use { hb ->
            hb.start()
            assertThat(waitFor { hb.cancelled }).isTrue()
            assertThat(hb.leaseLost).isFalse()
        }
        assertThat(core.heartbeats.first().requestsSent).isEqualTo(7)
    }

    @Test
    fun `lease loss is detected`() {
        val core = FakeCore(heartbeatResult = { HeartbeatResult.LeaseLost })
        LeaseHeartbeat(core, UUID.randomUUID(), 1, Duration.ofMillis(50), { 0 }, { 0 }).use { hb ->
            hb.start()
            assertThat(waitFor { hb.leaseLost }).isTrue()
        }
    }

    @Test
    fun `temporary core outage does not stop the heartbeat`() {
        var calls = 0
        val core = FakeCore(heartbeatResult = {
            calls++
            if (calls < 3) throw CoreUnavailableException("down") else HeartbeatResult.Ok(HeartbeatResponse(false, Instant.now().plusSeconds(60)))
        })
        LeaseHeartbeat(core, UUID.randomUUID(), 1, Duration.ofMillis(30), { 0 }, { 0 }).use { hb ->
            hb.start()
            assertThat(waitFor { core.heartbeats.size >= 3 }).isTrue()
            assertThat(hb.cancelled).isFalse()
        }
    }
}
