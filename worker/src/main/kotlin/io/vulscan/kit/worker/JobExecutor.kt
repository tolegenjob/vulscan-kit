package io.vulscan.kit.worker

import io.vulscan.kit.contracts.ClaimResponse
import io.vulscan.kit.contracts.JobErrorCode
import io.vulscan.kit.contracts.JobStats
import io.vulscan.kit.contracts.ScanJobPayload
import io.vulscan.kit.net.Cidr
import io.vulscan.kit.net.HttpLimits
import io.vulscan.kit.net.IpPolicy
import io.vulscan.kit.net.ProbeNetworkException
import io.vulscan.kit.net.ProbeRequest
import io.vulscan.kit.net.ProbeResponse
import io.vulscan.kit.net.ProbeTimeoutException
import io.vulscan.kit.net.SafeHttpClient
import io.vulscan.kit.net.ScopeMatcher
import io.vulscan.kit.net.ScopeViolationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Создаёт функцию отправки запросов для задания. В production — [SafeHttpClient], в тестах подменяется. */
fun interface HttpFactory {
    fun create(job: ScanJobPayload, allowedPrivateCidrs: List<Cidr>): (ProbeRequest) -> ProbeResponse
}

object DefaultHttpFactory : HttpFactory {
    override fun create(job: ScanJobPayload, allowedPrivateCidrs: List<Cidr>): (ProbeRequest) -> ProbeResponse {
        val client = SafeHttpClient(
            scope = ScopeMatcher(job.target.allowedHosts, job.scope.includePrefixes, job.scope.excludePrefixes),
            policy = IpPolicy(allowedPrivateCidrs),
            limits = HttpLimits(job.limits.httpTimeoutMs, job.limits.maxResponseBytes),
        )
        return client::execute
    }
}

/** Выполняет один уже захваченный (claim) job и публикует итог. Не бросает исключений наружу. */
class JobExecutor(
    private val engine: DetectionEngine,
    private val core: CoreControlClient,
    private val publisher: ResultPublisher,
    properties: WorkerProperties,
    private val httpFactory: HttpFactory = DefaultHttpFactory,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(JobExecutor::class.java)
    private val privateCidrs: List<Cidr> = properties.allowedPrivateCidrs.map(Cidr::parse)

    /** Передаёт ссылки между объектами, которые зависят друг от друга (heartbeat ↔ http). */
    private class Wiring {
        @Volatile var heartbeat: LeaseHeartbeat? = null
        @Volatile var http: BudgetedHttp? = null
    }

    fun execute(job: ScanJobPayload, correlationId: UUID, claim: ClaimResponse) {
        val started = System.nanoTime()
        val stats = MutableJobStats()
        val wiring = Wiring()

        val send = try {
            httpFactory.create(job, privateCidrs)
        } catch (e: IllegalArgumentException) {
            safely { publisher.publishFailed(job.jobId, job.attempt, correlationId, JobErrorCode.INVALID_JOB, "Invalid scope configuration", false) }
            return
        }

        val isStopped = { wiring.heartbeat?.let { it.cancelled || it.leaseLost } ?: false }
        val http = BudgetedHttp(send, RateLimiter(job.limits.requestsPerSecond), job.limits.maxRequests, job.limits.maxConcurrency, job.deadlineAt, isStopped, clock)
        wiring.http = http
        val heartbeat = LeaseHeartbeat(
            core, job.jobId, job.attempt,
            Duration.ofSeconds(claim.heartbeatIntervalSec.toLong().coerceAtLeast(1)),
            requestsSent = { http.requestsSent },
            endpointsDone = { stats.testedCount() },
        )
        wiring.heartbeat = heartbeat

        val ctx = JobContext(
            job = job,
            http = http,
            stats = stats,
            reporter = { finding -> reportFinding(job, correlationId, stats, finding) },
            cancelled = isStopped,
            identityLoader = { core.fetchIdentities(job.jobId, job.attempt) },
        )

        heartbeat.start()
        try {
            engine.run(ctx)
            if (heartbeat.leaseLost || heartbeat.cancelled) return
            complete(job, correlationId, stats, http, started)
        } catch (e: BudgetExhaustedException) {
            stats.budgetExhausted = true
            complete(job, correlationId, stats, http, started)
        } catch (e: CancelledException) {
            log.info("Job {} cancelled", job.jobId)
        } catch (e: LeaseLostException) {
            log.warn("Job {} lost its lease", job.jobId)
        } catch (e: DeadlineExceededException) {
            fail(job, correlationId, JobErrorCode.DEADLINE_EXCEEDED, "Job deadline exceeded", false, stats, http, started)
        } catch (e: InvalidJobException) {
            fail(job, correlationId, JobErrorCode.INVALID_JOB, e.message?.lineSequence()?.firstOrNull() ?: "Invalid job", false, stats, http, started)
        } catch (e: AuthFailedException) {
            fail(job, correlationId, JobErrorCode.AUTH_FAILED, e.message?.lineSequence()?.firstOrNull() ?: "Authentication failed", false, stats, http, started)
        } catch (e: ScopeViolationException) {
            fail(job, correlationId, JobErrorCode.SCOPE_VIOLATION, "Request was blocked by scope policy: ${e.reason}", false, stats, http, started)
        } catch (e: ProbeTimeoutException) {
            fail(job, correlationId, JobErrorCode.TARGET_UNREACHABLE, "Target did not respond in time", true, stats, http, started)
        } catch (e: ProbeNetworkException) {
            fail(job, correlationId, JobErrorCode.TARGET_UNREACHABLE, "Target is unreachable", true, stats, http, started)
        } catch (e: Throwable) {
            log.error("Job {} failed", job.jobId, e)
            fail(job, correlationId, JobErrorCode.INTERNAL_ERROR, "Internal error: ${e.javaClass.simpleName}", true, stats, http, started)
        } finally {
            heartbeat.close()
        }
    }

    private fun reportFinding(job: ScanJobPayload, correlationId: UUID, stats: MutableJobStats, finding: io.vulscan.kit.contracts.FindingPayload) {
        val sanitized = try {
            FindingSanitizer.apply(finding)
        } catch (e: IllegalArgumentException) {
            log.warn("Finding dropped for job {}: {}", job.jobId, e.message)
            stats.inconclusive()
            return
        }
        publisher.publishFinding(job.jobId, job.attempt, correlationId, sanitized)
        stats.findingReported()
    }

    private fun snapshot(stats: MutableJobStats, http: BudgetedHttp, started: Long): JobStats =
        stats.snapshot(http.requestsSent, (System.nanoTime() - started) / 1_000_000)

    private fun complete(job: ScanJobPayload, correlationId: UUID, stats: MutableJobStats, http: BudgetedHttp, started: Long) {
        safely { publisher.publishCompleted(job.jobId, job.attempt, correlationId, snapshot(stats, http, started)) }
    }

    private fun fail(
        job: ScanJobPayload,
        correlationId: UUID,
        code: JobErrorCode,
        message: String,
        retryable: Boolean,
        stats: MutableJobStats,
        http: BudgetedHttp,
        started: Long,
    ) {
        safely { publisher.publishFailed(job.jobId, job.attempt, correlationId, code, message, retryable, snapshot(stats, http, started)) }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.error("Failed to publish job result", e)
        }
    }
}
