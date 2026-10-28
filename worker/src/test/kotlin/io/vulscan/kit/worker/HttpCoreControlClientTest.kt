package io.vulscan.kit.worker

import com.sun.net.httpserver.HttpServer
import io.vulscan.kit.contracts.ClaimRequest
import io.vulscan.kit.contracts.HeartbeatRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class HttpCoreControlClientTest {
    private lateinit var server: HttpServer
    private val status = AtomicReference(200)
    private val responseBody = AtomicReference("")
    private val lastAuth = AtomicReference<String?>()
    private val lastWorker = AtomicReference<String?>()
    private val lastPath = AtomicReference<String?>()
    private val lastRequestBody = AtomicReference<String?>()

    private fun client() = HttpCoreControlClient("http://127.0.0.1:${server.address.port}", "tok", "w1")

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { ex ->
            lastAuth.set(ex.requestHeaders.getFirst("Authorization"))
            lastWorker.set(ex.requestHeaders.getFirst("X-Worker-Id"))
            lastPath.set(ex.requestURI.toString())
            lastRequestBody.set(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = responseBody.get().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status.get(), if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `claim success sends credentials and parses the response`() {
        responseBody.set("""{"leaseExpiresAt":"2026-10-28T12:00:00Z","heartbeatIntervalSec":15}""")
        val jobId = UUID.randomUUID()
        val result = client().claim(jobId, ClaimRequest(1, "w1"))
        assertThat(result).isInstanceOf(ClaimResult.Granted::class.java)
        assertThat((result as ClaimResult.Granted).response.heartbeatIntervalSec).isEqualTo(15)
        assertThat(lastAuth.get()).isEqualTo("Bearer tok")
        assertThat(lastWorker.get()).isEqualTo("w1")
        assertThat(lastPath.get()).isEqualTo("/internal/v1/jobs/$jobId/claim")
        assertThat(lastRequestBody.get()).contains("\"attempt\":1").contains("\"workerId\":\"w1\"")
    }

    @Test
    fun `conflict and gone are rejections`() {
        status.set(409)
        assertThat(client().claim(UUID.randomUUID(), ClaimRequest(1, "w1"))).isEqualTo(ClaimResult.Rejected(409))
        status.set(410)
        assertThat(client().claim(UUID.randomUUID(), ClaimRequest(1, "w1"))).isEqualTo(ClaimResult.Rejected(410))
    }

    @Test
    fun `server error is reported as unavailable`() {
        status.set(503)
        assertThatThrownBy { client().claim(UUID.randomUUID(), ClaimRequest(1, "w1")) }.isInstanceOf(CoreUnavailableException::class.java)
    }

    @Test
    fun `unreachable core is reported as unavailable`() {
        val c = HttpCoreControlClient("http://127.0.0.1:1", "tok", "w1")
        assertThatThrownBy { c.claim(UUID.randomUUID(), ClaimRequest(1, "w1")) }.isInstanceOf(CoreUnavailableException::class.java)
    }

    @Test
    fun `heartbeat conflict means lease lost`() {
        status.set(409)
        assertThat(client().heartbeat(UUID.randomUUID(), HeartbeatRequest(1, 5, 0))).isEqualTo(HeartbeatResult.LeaseLost)
    }

    @Test
    fun `heartbeat success carries the cancel flag`() {
        responseBody.set("""{"cancel":true,"leaseExpiresAt":"2026-10-28T12:00:00Z"}""")
        val result = client().heartbeat(UUID.randomUUID(), HeartbeatRequest(1, 5, 0)) as HeartbeatResult.Ok
        assertThat(result.response.cancel).isTrue()
    }

    @Test
    fun `identities are parsed from a json array`() {
        val id = UUID.randomUUID()
        responseBody.set("""[{"identityId":"$id","label":"alice","role":"USER","auth":{"type":"BEARER_STATIC","secret":{"token":"t"}}}]""")
        val list = client().fetchIdentities(UUID.randomUUID(), 1)
        assertThat(list).hasSize(1)
        assertThat(list[0].label).isEqualTo("alice")
        assertThat(list[0].toString()).doesNotContain("\"t\"")
    }
}
