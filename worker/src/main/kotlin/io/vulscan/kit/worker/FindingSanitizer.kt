package io.vulscan.kit.worker

import io.vulscan.kit.contracts.Evidence
import io.vulscan.kit.contracts.FindingPayload
import io.vulscan.kit.contracts.Highlight
import io.vulscan.kit.contracts.HttpRequestEvidence
import io.vulscan.kit.contracts.HttpResponseEvidence
import io.vulscan.kit.redact.Redactor

/** Последний барьер перед публикацией finding: маскирование секретов и ограничение размеров. */
object FindingSanitizer {
    const val MAX_RESPONSE_EXCERPT = 2048
    const val MAX_REQUEST_EXCERPT = 2048
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun apply(finding: FindingPayload): FindingPayload {
        require(finding.confidence in 1..99) { "confidence must be within 1..99" }
        require(HEX64.matches(finding.fingerprint)) { "fingerprint must be 64 lowercase hex chars" }
        val evidence = finding.evidence
        val request = evidence.request.let { req ->
            HttpRequestEvidence(
                method = req.method,
                url = Redactor.url(req.url),
                headers = Redactor.headers(req.headers),
                bodyExcerpt = req.bodyExcerpt?.let { Redactor.excerpt(Redactor.body(it), MAX_REQUEST_EXCERPT) },
            )
        }
        val response = evidence.response?.let { res -> sanitizeResponse(res) }
        return finding.copy(
            url = Redactor.url(finding.url),
            evidence = Evidence(request, response, evidence.observations),
        )
    }

    private fun sanitizeResponse(res: HttpResponseEvidence): HttpResponseEvidence {
        val excerpt = res.bodyExcerpt?.let { Redactor.body(it) }
        val limited = excerpt?.let { if (it.length > MAX_RESPONSE_EXCERPT) it.substring(0, safeCut(it, MAX_RESPONSE_EXCERPT)) else it }
        val highlights: List<Highlight> = if (limited == null) {
            emptyList()
        } else {
            // Маскирование и усечение могут сдвинуть текст: подсветка сохраняется только если тело не менялось
            if (limited == res.bodyExcerpt) res.highlights.filter { it.start in 0 until it.end && it.end <= limited.length } else emptyList()
        }
        return HttpResponseEvidence(res.status, Redactor.headers(res.headers), limited, highlights)
    }

    private fun safeCut(text: String, max: Int): Int =
        if (max > 0 && Character.isHighSurrogate(text[max - 1])) max - 1 else max
}
