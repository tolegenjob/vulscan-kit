package io.vulscan.kit.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.vulscan.kit.contracts.AllowedHost
import io.vulscan.kit.contracts.HttpMethod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SafeHttpClientTest {

    private lateinit var server: HttpServer
    private val port get() = server.address.port
    private val hits = CopyOnWriteArrayList<String>()
    private val cookieHeaders = CopyOnWriteArrayList<String?>()
    private val loopback = InetAddress.getByName("127.0.0.1")

    private val resolver = HostResolver { host ->
        when (host) {
            "app.test", "other.test" -> listOf(loopback)
            "internal.test" -> listOf(InetAddress.getByName("10.0.0.5"))
            "mixed.test" -> listOf(loopback, InetAddress.getByName("10.0.0.5"))
            else -> throw java.net.UnknownHostException(host)
        }
    }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        hits += path
        cookieHeaders += ex.requestHeaders.getFirst("Cookie")
        when {
            path == "/redirect-out" -> redirect(ex, "http://other.test:$port/x")
            path == "/redirect-internal" -> redirect(ex, "http://internal.test:$port/x")
            path == "/redirect-ok" -> redirect(ex, "http://app.test:$port/ok")
            path == "/loop" -> redirect(ex, "http://app.test:$port/loop")
            path == "/big" -> respond(ex, 200, ByteArray(5 * 1024 * 1024) { 'a'.code.toByte() })
            path == "/slow" -> {
                Thread.sleep(1500)
                respond(ex, 200, "late".toByteArray())
            }
            path == "/set-cookie" -> {
                ex.responseHeaders.add("Set-Cookie", "sid=abc; Path=/")
                respond(ex, 200, "ok".toByteArray())
            }
            else -> respond(ex, 200, "hello".toByteArray())
        }
    }

    private fun redirect(ex: HttpExchange, to: String) {
        ex.responseHeaders.add("Location", to)
        ex.sendResponseHeaders(302, -1)
        ex.close()
    }

    private fun respond(ex: HttpExchange, status: Int, body: ByteArray) {
        try {
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        } catch (_: java.io.IOException) {
            // клиент мог закрыть соединение (усечённое тело, таймаут)
        }
    }

    private fun client(
        hosts: List<String> = listOf("app.test", "other.test", "internal.test", "mixed.test"),
        allowedHosts: List<String> = listOf("app.test", "internal.test", "mixed.test"),
        timeoutMs: Int = 3000,
        maxBytes: Int = 1024 * 1024,
    ): SafeHttpClient {
        check(hosts.isNotEmpty())
        return SafeHttpClient(
            scope = ScopeMatcher(
                allowedHosts = allowedHosts.map { AllowedHost(it, listOf(port)) },
                includePrefixes = allowedHosts.map { "http://$it:$port/" },
            ),
            policy = IpPolicy(listOf(Cidr.parse("127.0.0.0/8"))),
            limits = HttpLimits(timeoutMs = timeoutMs, maxResponseBytes = maxBytes),
            resolver = resolver,
        )
    }

    private fun get(url: String) = ProbeRequest(HttpMethod.GET, url)

    @Test
    fun `allowed request succeeds`() {
        val response = client().execute(get("http://app.test:$port/hello"))
        assertThat(response.status).isEqualTo(200)
        assertThat(response.bodyText()).isEqualTo("hello")
        assertThat(response.truncated).isFalse()
        assertThat(response.redirectChain).hasSize(1)
    }

    @Test
    fun `out of scope request never reaches the server`() {
        assertThatThrownBy { client().execute(get("http://other.test:$port/secret")) }
            .isInstanceOf(ScopeViolationException::class.java)
        assertThat(hits).isEmpty()
    }

    @Test
    fun `redirect to a host outside scope is blocked`() {
        val response = client().execute(get("http://app.test:$port/redirect-out"))
        assertThat(response.status).isEqualTo(302)
        assertThat(response.redirectBlocked).isTrue()
        assertThat(response.redirectBlockedReason).isNotNull()
        assertThat(hits).containsExactly("/redirect-out")
    }

    @Test
    fun `redirect inside scope is followed`() {
        val response = client().execute(get("http://app.test:$port/redirect-ok"))
        assertThat(response.status).isEqualTo(200)
        assertThat(response.redirectChain).hasSize(2)
        assertThat(response.finalUrl).endsWith("/ok")
    }

    @Test
    fun `redirect to a host resolving to a private address is rejected`() {
        assertThatThrownBy { client().execute(get("http://app.test:$port/redirect-internal")) }
            .isInstanceOf(ScopeViolationException::class.java)
        assertThat(hits).containsExactly("/redirect-internal")
    }

    @Test
    fun `host resolving to a forbidden address is rejected before connecting`() {
        assertThatThrownBy { client().execute(get("http://internal.test:$port/x")) }
            .isInstanceOf(ScopeViolationException::class.java)
        assertThat(hits).isEmpty()
    }

    @Test
    fun `one forbidden address among several rejects the host`() {
        assertThatThrownBy { client().execute(get("http://mixed.test:$port/x")) }
            .isInstanceOf(ScopeViolationException::class.java)
        assertThat(hits).isEmpty()
    }

    @Test
    fun `redirect loop stops at the limit`() {
        val response = client().execute(get("http://app.test:$port/loop"))
        assertThat(response.redirectBlocked).isTrue()
        assertThat(response.redirectBlockedReason).isEqualTo(ScopeDenial.REDIRECT_LIMIT)
        assertThat(hits.size).isEqualTo(4)
    }

    @Test
    fun `large body is truncated at the limit`() {
        val response = client(maxBytes = 1024 * 1024).execute(get("http://app.test:$port/big"))
        assertThat(response.status).isEqualTo(200)
        assertThat(response.truncated).isTrue()
        assertThat(response.body.size).isEqualTo(1024 * 1024)
    }

    @Test
    fun `slow response raises timeout`() {
        assertThatThrownBy { client(timeoutMs = 300).execute(get("http://app.test:$port/slow")) }
            .isInstanceOf(ProbeTimeoutException::class.java)
    }

    @Test
    fun `cookies are not kept between requests`() {
        val c = client()
        c.execute(get("http://app.test:$port/set-cookie"))
        c.execute(get("http://app.test:$port/hello"))
        assertThat(cookieHeaders.last()).isNull()
    }

    @Test
    fun `ip literal in a forbidden range is rejected`() {
        val c = SafeHttpClient(
            scope = ScopeMatcher(listOf(AllowedHost("169.254.169.254", listOf(80))), listOf("http://169.254.169.254/")),
            policy = IpPolicy(listOf(Cidr.parse("169.254.0.0/16"))),
            limits = HttpLimits(1000, 1024),
            resolver = resolver,
        )
        assertThatThrownBy { c.execute(get("http://169.254.169.254/latest/meta-data/")) }
            .isInstanceOf(ScopeViolationException::class.java)
    }

    @Test
    fun `post redirect with 302 becomes get`() {
        val counter = AtomicInteger()
        server.createContext("/post-redirect") { ex ->
            counter.incrementAndGet()
            redirect(ex, "http://app.test:$port/hello")
        }
        val response = client().execute(
            ProbeRequest(HttpMethod.POST, "http://app.test:$port/post-redirect", body = "a=1".toByteArray(), contentType = "application/x-www-form-urlencoded"),
        )
        assertThat(response.status).isEqualTo(200)
        assertThat(counter.get()).isEqualTo(1)
    }
}
