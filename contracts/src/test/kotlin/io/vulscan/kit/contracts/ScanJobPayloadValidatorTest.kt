package io.vulscan.kit.contracts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

class ScanJobPayloadValidatorTest {

    /** Момент до deadlineAt из примеров (2026-10-22T15:34:05Z). */
    private val now = Instant.parse("2026-10-22T15:00:00Z")

    private fun job(name: String): ScanJobPayload =
        Json.payloadAs(Json.readEnvelope(ContractExamples.valid(name)), ScanJobPayload::class.java)

    private fun codes(payload: ScanJobPayload): List<ViolationCode> =
        ScanJobPayloadValidator.validate(payload, now).map { it.code }

    @ParameterizedTest
    @ValueSource(strings = ["scan-job-xss.json", "scan-job-sqli.json", "scan-job-idor.json"])
    fun `valid examples have no violations`(name: String) {
        assertThat(codes(job(name))).isEmpty()
    }

    @Test
    fun `moduleOptions branch must match moduleType`() {
        val p = job("scan-job-xss.json").copy(moduleType = ModuleType.SQLI)
        assertThat(codes(p)).contains(ViolationCode.MODULE_OPTIONS_MISMATCH)
    }

    @Test
    fun `two option branches are rejected`() {
        val p = job("scan-job-xss.json")
        val both = p.copy(moduleOptions = p.moduleOptions.copy(sqli = SqliOptions()))
        assertThat(codes(both)).contains(ViolationCode.MODULE_OPTIONS_MISMATCH)
    }

    @Test
    fun `deadline in the past is rejected`() {
        val p = job("scan-job-xss.json").copy(deadlineAt = now.minusSeconds(1))
        assertThat(codes(p)).contains(ViolationCode.DEADLINE_IN_PAST)
    }

    @Test
    fun `non positive limit is rejected`() {
        val p = job("scan-job-xss.json")
        val bad = p.copy(limits = p.limits.copy(requestsPerSecond = 0))
        assertThat(codes(bad)).contains(ViolationCode.NON_POSITIVE_LIMIT)
    }

    @Test
    fun `endpoint outside include prefixes or inside exclude prefixes is out of scope`() {
        val p = job("scan-job-xss.json")
        val outside = p.copy(endpoints = p.endpoints.map { it.copy(url = "http://evil.test/x") })
        val excluded = p.copy(endpoints = p.endpoints.map { it.copy(url = "http://lab-app:8080/logout") })
        assertThat(codes(outside)).contains(ViolationCode.ENDPOINT_OUT_OF_SCOPE)
        assertThat(codes(excluded)).contains(ViolationCode.ENDPOINT_OUT_OF_SCOPE)
    }

    @Test
    fun `empty endpoints and empty allowed hosts are rejected`() {
        val p = job("scan-job-xss.json")
        val bad = p.copy(endpoints = emptyList(), target = p.target.copy(allowedHosts = emptyList()))
        assertThat(codes(bad)).contains(ViolationCode.NO_ENDPOINTS, ViolationCode.NO_ALLOWED_HOSTS)
    }

    @Test
    fun `more than 200 endpoints are rejected`() {
        val p = job("scan-job-xss.json")
        val many = List(201) { p.endpoints.first().copy(endpointId = UUID.randomUUID()) }
        assertThat(codes(p.copy(endpoints = many))).contains(ViolationCode.TOO_MANY_ENDPOINTS)
    }

    @Test
    fun `idor READ_ONLY rejects non GET endpoints`() {
        val p = job("scan-job-idor.json")
        val bad = p.copy(endpoints = p.endpoints.map { it.copy(method = HttpMethod.PUT) })
        assertThat(codes(bad)).contains(ViolationCode.READ_ONLY_VIOLATION)
    }

    @Test
    fun `idor needs two identities and known references`() {
        val p = job("scan-job-idor.json")
        val idor = p.moduleOptions.idor!!
        val oneIdentity = p.copy(moduleOptions = ModuleOptions(idor = idor.copy(identities = idor.identities.take(1))))
        assertThat(codes(oneIdentity))
            .contains(ViolationCode.IDOR_TOO_FEW_IDENTITIES, ViolationCode.IDOR_UNKNOWN_IDENTITY)

        val unknownEndpoint = p.copy(
            moduleOptions = ModuleOptions(
                idor = idor.copy(resources = idor.resources.map { it.copy(endpointId = UUID.randomUUID()) }),
            ),
        )
        assertThat(codes(unknownEndpoint)).contains(ViolationCode.IDOR_UNKNOWN_ENDPOINT)
    }
}
