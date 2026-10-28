package io.vulscan.kit.net

import io.vulscan.kit.contracts.HttpMethod
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.time.Duration

const val DEFAULT_USER_AGENT = "VulScanDiploma/0.1 (+authorized-testing)"

data class HttpLimits(
    val timeoutMs: Int,
    val maxResponseBytes: Int,
    val maxRedirects: Int = 3,
)

class ProbeRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
)

class ProbeResponse(
    val status: Int,
    /** Ключи в нижнем регистре, несколько значений объединены через ", ". */
    val headers: Map<String, String>,
    val body: ByteArray,
    /** true, если тело длиннее лимита и было обрезано. */
    val truncated: Boolean,
    val elapsedMs: Long,
    val finalUrl: String,
    val redirectChain: List<String>,
    /** Редирект не выполнен (вне scope, запрещённый адрес или лимит); тогда status — код 3xx. */
    val redirectBlocked: Boolean = false,
    val redirectBlockedReason: ScopeDenial? = null,
) {
    fun bodyText(): String = body.toString(Charsets.UTF_8)
}

class ProbeTimeoutException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ProbeNetworkException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Единственный разрешённый способ отправлять запросы к целям сканирования.
 *
 * Гарантии:
 *  - каждый запрос и каждый hop редиректа проходит [ScopeMatcher];
 *  - адреса назначения проверяет [IpPolicy] до подключения (в DNS и для литералов IP)
 *    и повторно после установки соединения;
 *  - редиректы выполняются вручную, не более [HttpLimits.maxRedirects];
 *  - тело читается не более [HttpLimits.maxResponseBytes] (после распаковки);
 *  - прокси, кэш и cookie отключены (cookie только через явно переданный [CookieJar] идентичности).
 */
class SafeHttpClient(
    private val scope: ScopeMatcher,
    policy: IpPolicy,
    private val limits: HttpLimits,
    resolver: HostResolver = SystemHostResolver,
    private val userAgent: String = DEFAULT_USER_AGENT,
    cookieJar: CookieJar = CookieJar.NO_COOKIES,
) {
    private val dns = SafeDns(policy, resolver)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .proxy(Proxy.NO_PROXY)
        .cookieJar(cookieJar)
        .connectTimeout(Duration.ofMillis(limits.timeoutMs.toLong()))
        .readTimeout(Duration.ofMillis(limits.timeoutMs.toLong()))
        .writeTimeout(Duration.ofMillis(limits.timeoutMs.toLong()))
        .callTimeout(Duration.ofMillis(limits.timeoutMs.toLong()))
        .addNetworkInterceptor(AddressGuard(policy))
        .build()

    fun execute(request: ProbeRequest): ProbeResponse {
        val started = System.nanoTime()
        val chain = mutableListOf<String>()
        var method = request.method
        var body = request.body
        var currentUrl = request.url
        var redirects = 0

        while (true) {
            val url = when (val decision = scope.check(currentUrl)) {
                is ScopeDecision.Allowed -> decision.url
                is ScopeDecision.Denied -> throw ScopeViolationException(decision.reason, decision.detail)
            }
            chain += url.toString()
            val raw = send(method, url, request, body)

            val location = raw.headers["location"]
            val isRedirect = raw.status in REDIRECT_STATUSES && location != null
            if (!isRedirect) {
                return raw.toResponse(started, url.toString(), chain)
            }

            val next = url.resolve(location!!)
            val blockReason = when {
                next == null -> ScopeDenial.BAD_URL
                redirects >= limits.maxRedirects -> ScopeDenial.REDIRECT_LIMIT
                else -> (scope.check(next.toString()) as? ScopeDecision.Denied)?.reason
            }
            if (blockReason != null) {
                return raw.toResponse(started, url.toString(), chain, blocked = true, reason = blockReason)
            }
            // Адрес следующего hop проверяется до отправки (ошибка здесь — нарушение scope, а не сетевой сбой)
            redirects++
            currentUrl = next.toString()
            if (raw.status == 303 || ((raw.status == 301 || raw.status == 302) && method == HttpMethod.POST)) {
                if (method != HttpMethod.HEAD) method = HttpMethod.GET
                body = null
            }
        }
    }

    private class Raw(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
        val truncated: Boolean,
    ) {
        fun toResponse(
            started: Long,
            finalUrl: String,
            chain: List<String>,
            blocked: Boolean = false,
            reason: ScopeDenial? = null,
        ) = ProbeResponse(
            status = status,
            headers = headers,
            body = body,
            truncated = truncated,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            finalUrl = finalUrl,
            redirectChain = chain,
            redirectBlocked = blocked,
            redirectBlockedReason = reason,
        )
    }

    private fun send(method: HttpMethod, url: HttpUrl, original: ProbeRequest, body: ByteArray?): Raw {
        // Полная проверка адреса до подключения, в том числе для IP-литералов, которые OkHttp не резолвит
        dns.lookup(url.host)

        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        original.headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(method.name, requestBody(method, body, original.contentType))

        try {
            client.newCall(builder.build()).execute().use { response ->
                return read(response)
            }
        } catch (e: IOException) {
            throw translate(e)
        }
    }

    private fun requestBody(method: HttpMethod, body: ByteArray?, contentType: String?): RequestBody? {
        val needsBody = method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH
        if (!needsBody && body == null) return null
        return (body ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull())
    }

    private fun read(response: Response): Raw {
        val headers = response.headers.toMultimap().mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(", ") }
        val max = limits.maxResponseBytes.toLong()
        val buffer = Buffer()
        var total = 0L
        val source = response.body.source()
        while (total <= max) {
            val read = source.read(buffer, minOf(8192L, max + 1 - total))
            if (read == -1L) break
            total += read
        }
        val truncated = total > max
        val bytes = buffer.readByteArray(minOf(total, max))
        return Raw(response.code, headers, bytes, truncated)
    }

    private fun translate(e: IOException): Exception {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is ScopeViolationException) return cause
            cause = cause.cause
        }
        if (e is InterruptedIOException) return ProbeTimeoutException("Request timed out", e)
        return ProbeNetworkException("Network error: ${e.javaClass.simpleName}", e)
    }

    /** Повторная проверка фактического адреса после установки соединения. */
    private class AddressGuard(private val policy: IpPolicy) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val address = chain.connection()?.socket()?.inetAddress
            if (address != null) {
                val verdict = policy.check(address)
                if (verdict != IpVerdict.ALLOWED) {
                    throw ScopeViolationException(ScopeDenial.PRIVATE_ADDRESS, "Connected to a forbidden address ($verdict)")
                }
            }
            return chain.proceed(chain.request())
        }
    }

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
