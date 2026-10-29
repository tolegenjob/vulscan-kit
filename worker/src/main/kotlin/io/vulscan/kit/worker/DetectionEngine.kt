package io.vulscan.kit.worker

import io.vulscan.kit.contracts.FindingPayload
import io.vulscan.kit.contracts.IdentityCredential
import io.vulscan.kit.contracts.JobStats
import io.vulscan.kit.contracts.ModuleType
import io.vulscan.kit.contracts.ScanJobPayload
import io.vulscan.kit.contracts.SkippedEndpoint
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Единственное, что обязан реализовать модуль обнаружения. */
interface DetectionEngine {
    val moduleType: ModuleType

    /** Выполняет задание; выбрасывает [JobAbortException] при прерывании рантаймом (его пробрасывать дальше). */
    fun run(ctx: JobContext)
}

class MutableJobStats {
    private val findings = AtomicInteger()
    private val tested = AtomicInteger()
    private val inconclusiveCount = AtomicInteger()
    private val skipped = CopyOnWriteArrayList<SkippedEndpoint>()

    @Volatile
    var budgetExhausted: Boolean = false

    fun findingReported() {
        findings.incrementAndGet()
    }

    fun endpointTested() {
        tested.incrementAndGet()
    }

    fun testedCount(): Int = tested.get()

    fun inconclusive() {
        inconclusiveCount.incrementAndGet()
    }

    fun skipped(endpointId: UUID, reason: String) {
        skipped += SkippedEndpoint(endpointId, reason)
    }

    fun snapshot(requestsSent: Int, durationMs: Long) = JobStats(
        findingsCount = findings.get(),
        requestsSent = requestsSent,
        durationMs = durationMs,
        endpointsTested = tested.get(),
        endpointsSkipped = skipped.toList(),
        inconclusive = inconclusiveCount.get(),
        budgetExhausted = budgetExhausted,
    )
}

class JobContext(
    val job: ScanJobPayload,
    val http: BudgetedHttp,
    val stats: MutableJobStats,
    private val reporter: (FindingPayload) -> Unit,
    private val cancelled: () -> Boolean,
    private val identityLoader: () -> List<IdentityCredential>,
) {
    fun report(finding: FindingPayload) {
        reporter(finding)
    }

    fun isCancelled(): Boolean = cancelled()

    /** Учётные данные тестовых идентичностей (только IDOR). Содержит секреты, не логировать. */
    fun identities(): List<IdentityCredential> = identityLoader()
}
