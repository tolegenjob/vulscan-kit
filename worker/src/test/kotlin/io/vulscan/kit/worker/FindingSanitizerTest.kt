package io.vulscan.kit.worker

import io.vulscan.kit.contracts.Evidence
import io.vulscan.kit.contracts.Highlight
import io.vulscan.kit.contracts.HttpResponseEvidence
import io.vulscan.kit.redact.Redactor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FindingSanitizerTest {

    @Test
    fun `secrets are masked in request evidence`() {
        val base = Fixtures.finding()
        val finding = base.copy(
            url = "http://u:p@lab-app:8080/x?token=abc&q=1",
            evidence = base.evidence.copy(
                request = base.evidence.request.copy(headers = mapOf("Authorization" to "Bearer abc", "Accept" to "text/html")),
            ),
        )
        val result = FindingSanitizer.apply(finding)
        assertThat(result.url).contains(Redactor.MASK).doesNotContain("abc").doesNotContain("u:p")
        assertThat(result.evidence.request.headers["Authorization"]).isEqualTo(Redactor.MASK)
        assertThat(result.evidence.request.headers["Accept"]).isEqualTo("text/html")
    }

    @Test
    fun `long response excerpt is truncated and highlights stay valid`() {
        val base = Fixtures.finding()
        val body = "x".repeat(5000)
        val finding = base.copy(
            evidence = Evidence(
                base.evidence.request,
                HttpResponseEvidence(200, emptyMap(), body, listOf(Highlight(10, 20))),
            ),
        )
        val response = FindingSanitizer.apply(finding).evidence.response!!
        assertThat(response.bodyExcerpt!!.length).isLessThanOrEqualTo(FindingSanitizer.MAX_RESPONSE_EXCERPT)
        response.highlights.forEach { assertThat(it.end).isLessThanOrEqualTo(response.bodyExcerpt!!.length) }
    }

    @Test
    fun `intact excerpt keeps its highlights`() {
        val finding = Fixtures.finding()
        val result = FindingSanitizer.apply(finding)
        assertThat(result.evidence.response!!.highlights).isEqualTo(finding.evidence.response!!.highlights)
    }

    @Test
    fun `invalid confidence or fingerprint is rejected`() {
        val base = Fixtures.finding()
        assertThatThrownBy { FindingSanitizer.apply(base.copy(confidence = 100)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FindingSanitizer.apply(base.copy(fingerprint = "abc")) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
