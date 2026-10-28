package io.vulscan.kit.worker

import io.vulscan.kit.contracts.ClaimRequest
import io.vulscan.kit.contracts.ClaimResponse
import io.vulscan.kit.contracts.HeartbeatRequest
import io.vulscan.kit.contracts.HeartbeatResponse
import io.vulscan.kit.contracts.IdentityCredential
import io.vulscan.kit.contracts.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import java.util.UUID

sealed interface ClaimResult {
    data class Granted(val response: ClaimResponse) : ClaimResult

    /** 409/410: задание уже занято, отменено или завершено; запускать его не нужно. */
    data class Rejected(val status: Int) : ClaimResult
}

sealed interface HeartbeatResult {
    data class Ok(val response: HeartbeatResponse) : HeartbeatResult

    /** 409/410: lease потерян. */
    data object LeaseLost : HeartbeatResult
}

interface CoreControlClient {
    /** @throws CoreUnavailableException при сетевой ошибке или неожиданном статусе. */
    fun claim(jobId: UUID, request: ClaimRequest): ClaimResult

    fun heartbeat(jobId: UUID, request: HeartbeatRequest): HeartbeatResult

    fun fetchIdentities(jobId: UUID, attempt: Int): List<IdentityCredential>
}

/** Клиент внутреннего API Core. Адрес Core доверенный (из конфигурации), поэтому обычный OkHttp, а не SafeHttpClient. */
class HttpCoreControlClient(
    baseUrl: String,
    private val serviceToken: String,
    private val workerId: String,
    timeout: Duration = Duration.ofSeconds(5),
) : CoreControlClient {
    private val base = baseUrl.trimEnd('/').toHttpUrl()
    private val json = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .callTimeout(timeout)
        .followRedirects(false)
        .build()

    override fun claim(jobId: UUID, request: ClaimRequest): ClaimResult {
        val (status, body) = call(post("claim", jobId, Json.write(request)))
        return when (status) {
            200 -> ClaimResult.Granted(Json.read(body, ClaimResponse::class.java))
            409, 410 -> ClaimResult.Rejected(status)
            else -> throw CoreUnavailableException("Unexpected claim status $status")
        }
    }

    override fun heartbeat(jobId: UUID, request: HeartbeatRequest): HeartbeatResult {
        val (status, body) = call(post("heartbeat", jobId, Json.write(request)))
        return when (status) {
            200 -> HeartbeatResult.Ok(Json.read(body, HeartbeatResponse::class.java))
            409, 410 -> HeartbeatResult.LeaseLost
            else -> throw CoreUnavailableException("Unexpected heartbeat status $status")
        }
    }

    override fun fetchIdentities(jobId: UUID, attempt: Int): List<IdentityCredential> {
        val url = base.newBuilder()
            .addPathSegments("internal/v1/jobs/$jobId/identities")
            .addQueryParameter("attempt", attempt.toString())
            .build()
        val (status, body) = call(authorized(Request.Builder().url(url)).get().build())
        if (status != 200) throw CoreUnavailableException("Unexpected identities status $status")
        return Json.read(body, Array<IdentityCredential>::class.java).toList()
    }

    private fun post(action: String, jobId: UUID, body: String): Request {
        val url = base.newBuilder().addPathSegments("internal/v1/jobs/$jobId/$action").build()
        return authorized(Request.Builder().url(url)).post(body.toRequestBody(json)).build()
    }

    private fun authorized(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", "Bearer $serviceToken").header("X-Worker-Id", workerId)

    private fun call(request: Request): Pair<Int, String> =
        try {
            client.newCall(request).execute().use { it.code to it.body.string() }
        } catch (e: IOException) {
            throw CoreUnavailableException("Core is unreachable: ${e.javaClass.simpleName}", e)
        }
}
