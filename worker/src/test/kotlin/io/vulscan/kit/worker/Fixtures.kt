package io.vulscan.kit.worker

import io.vulscan.kit.contracts.ClaimRequest
import io.vulscan.kit.contracts.ClaimResponse
import io.vulscan.kit.contracts.ContractExamples
import io.vulscan.kit.contracts.Envelope
import io.vulscan.kit.contracts.FindingPayload
import io.vulscan.kit.contracts.FindingReportedPayload
import io.vulscan.kit.contracts.HeartbeatRequest
import io.vulscan.kit.contracts.IdentityCredential
import io.vulscan.kit.contracts.JobErrorCode
import io.vulscan.kit.contracts.JobStats
import io.vulscan.kit.contracts.Json
import io.vulscan.kit.contracts.MessageType
import io.vulscan.kit.contracts.ModuleType
import io.vulscan.kit.contracts.ScanJobPayload
import io.vulscan.kit.net.ProbeRequest
import io.vulscan.kit.net.ProbeResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object Fixtures {
    fun job(): ScanJobPayload =
        Json.payloadAs(Json.readEnvelope(ContractExamples.valid("scan-job-xss.json")), ScanJobPayload::class.java)
            .copy(deadlineAt = Instant.now().plusSeconds(3600))

    fun jobMessage(job: ScanJobPayload = job(), type: MessageType = MessageType.SCAN_JOB, correlationId: UUID = UUID.randomUUID()): String =
        Json.write(Envelope(type = type, correlationId = correlationId, payload = job))

    fun finding(): FindingPayload =
        Json.payloadAs(Json.readEnvelope(ContractExamples.valid("finding-xss.json")), FindingReportedPayload::class.java).finding

    fun response(status: Int = 200, body: String = "ok") = ProbeResponse(
        status = status, headers = emptyMap(), body = body.toByteArray(), truncated = false,
        elapsedMs = 1, finalUrl = "http://lab-app:8080/", redirectChain = listOf("http://lab-app:8080/"),
    )

    fun request() = ProbeRequest(io.vulscan.kit.contracts.HttpMethod.GET, "http://lab-app:8080/")
}

class FakeCore(
    var claimResult: () -> ClaimResult = { ClaimResult.Granted(ClaimResponse(Instant.now().plusSeconds(60), 1)) },
    var heartbeatResult: () -> HeartbeatResult = { HeartbeatResult.Ok(io.vulscan.kit.contracts.HeartbeatResponse(false, Instant.now().plusSeconds(60))) },
) : CoreControlClient {
    val claims = CopyOnWriteArrayList<UUID>()
    val heartbeats = CopyOnWriteArrayList<HeartbeatRequest>()

    override fun claim(jobId: UUID, request: ClaimRequest): ClaimResult {
        claims += jobId
        return claimResult()
    }

    override fun heartbeat(jobId: UUID, request: HeartbeatRequest): HeartbeatResult {
        heartbeats += request
        return heartbeatResult()
    }

    override fun fetchIdentities(jobId: UUID, attempt: Int): List<IdentityCredential> = emptyList()
}

class FakePublisher : ResultPublisher {
    val findings = CopyOnWriteArrayList<FindingPayload>()
    val completed = CopyOnWriteArrayList<JobStats>()
    data class Failure(val code: JobErrorCode, val message: String, val retryable: Boolean)
    val failed = CopyOnWriteArrayList<Failure>()

    override fun publishFinding(jobId: UUID, attempt: Int, correlationId: UUID, finding: FindingPayload) {
        findings += finding
    }

    override fun publishCompleted(jobId: UUID, attempt: Int, correlationId: UUID, stats: JobStats) {
        completed += stats
    }

    override fun publishFailed(jobId: UUID, attempt: Int, correlationId: UUID, code: JobErrorCode, message: String, retryable: Boolean, stats: JobStats?) {
        failed += Failure(code, message, retryable)
    }
}

class LambdaEngine(
    override val moduleType: ModuleType = ModuleType.XSS,
    private val body: (JobContext) -> Unit,
) : DetectionEngine {
    val runs = java.util.concurrent.atomic.AtomicInteger()

    override fun run(ctx: JobContext) {
        runs.incrementAndGet()
        body(ctx)
    }
}

fun testProperties(maxConcurrentJobs: Int = 1) = WorkerProperties(
    coreBaseUrl = "http://core.test", serviceToken = "secret-token", bootstrapServers = "localhost:9092",
    consumerGroup = "test", workerId = "w1", maxConcurrentJobs = maxConcurrentJobs,
)
