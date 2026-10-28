package io.vulscan.kit.contracts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FindingContractTest {

    private fun finding(name: String): FindingPayload {
        val raw = Json.readEnvelope(ContractExamples.valid(name))
        return Json.payloadAs(raw, FindingReportedPayload::class.java).finding
    }

    @ParameterizedTest
    @ValueSource(strings = ["finding-xss.json", "finding-sqli.json", "finding-idor.json"])
    fun `details type equals vulnType and class matches module`(name: String) {
        val f = finding(name)
        assertThat(f.details.type).isEqualTo(f.vulnType)
        when (f.moduleType) {
            ModuleType.XSS -> assertThat(f.details).isInstanceOf(XssDetails::class.java)
            ModuleType.SQLI -> assertThat(f.details).isInstanceOf(SqliDetails::class.java)
            ModuleType.IDOR -> assertThat(f.details).isInstanceOf(IdorDetails::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["finding-xss.json", "finding-sqli.json", "finding-idor.json"])
    fun `highlights stay inside the body excerpt`(name: String) {
        val response = finding(name).evidence.response
        val excerpt = response?.bodyExcerpt ?: return
        response.highlights.forEach {
            assertThat(it.start).isGreaterThanOrEqualTo(0)
            assertThat(it.end).isGreaterThan(it.start)
            assertThat(it.end).isLessThanOrEqualTo(excerpt.length)
        }
    }

    @Test
    fun `xss example fingerprint is computed by Fingerprint`() {
        val f = finding("finding-xss.json")
        val expected = Fingerprint.of("XSS", "REFLECTED", "GET", "/xss/search", "QUERY", "q", "HTML_TEXT")
        assertThat(f.fingerprint).isEqualTo(expected)
    }

    @Test
    fun `fingerprint is stable, hex and sensitive to every part`() {
        val a = Fingerprint.of("a", "b")
        assertThat(a).matches("^[0-9a-f]{64}$")
        assertThat(Fingerprint.of("a", "b")).isEqualTo(a)
        assertThat(Fingerprint.of("ab", "")).isNotEqualTo(a)
        assertThat(Fingerprint.of("b", "a")).isNotEqualTo(a)
    }

    @Test
    fun `details discriminator is written once and equals vulnType`() {
        val details: FindingDetails = XssDetails(
            type = VulnType.XSS_STORED,
            context = "HTML_TEXT",
            injectionProof = "DOM_STRUCTURE",
        )
        val json = Json.write(details)
        assertThat(json).contains("\"type\":\"XSS_STORED\"")
        assertThat(json.split("\"type\"").size - 1).isEqualTo(1)
    }
}
