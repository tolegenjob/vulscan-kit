package io.vulscan.kit.contracts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class JsonTest {

    @Test
    fun `unknown fields are ignored`() {
        val claim = Json.read("""{"attempt":2,"workerId":"w","somethingNew":true}""", ClaimRequest::class.java)
        assertThat(claim.attempt).isEqualTo(2)
    }

    @Test
    fun `instants are written as ISO-8601 strings and nulls are omitted`() {
        val json = Json.write(
            ClaimResponse(leaseExpiresAt = Instant.parse("2026-10-22T15:04:05Z"), heartbeatIntervalSec = 10),
        )
        assertThat(json).contains("\"leaseExpiresAt\":\"2026-10-22T15:04:05Z\"")

        val params = Json.write(ParameterRef(name = "q", location = ParamLocation.QUERY))
        assertThat(params).doesNotContain("sampleValue")
    }

    @Test
    fun `enums are written by name`() {
        assertThat(Json.write(ParameterId("id", ParamLocation.BODY_JSON))).contains("\"BODY_JSON\"")
    }

    @Test
    fun `topic names follow the module type`() {
        assertThat(KafkaTopics.jobsFor(ModuleType.XSS)).isEqualTo("scan.jobs.xss.v1")
        assertThat(KafkaTopics.jobsFor(ModuleType.SQLI)).isEqualTo("scan.jobs.sqli.v1")
        assertThat(KafkaTopics.jobsFor(ModuleType.IDOR)).isEqualTo("scan.jobs.idor.v1")
    }

    @Test
    fun `secrets never appear in toString of credentials`() {
        val credential = IdentityCredential(
            identityId = UUID.randomUUID(),
            label = "alice",
            role = "USER",
            auth = AuthSpec(
                type = AuthType.FORM_LOGIN,
                config = mapOf("loginUrl" to "http://lab-app:8080/login"),
                secret = mapOf("password" to "S3cretPassw0rd!"),
            ),
        )
        assertThat(credential.toString()).doesNotContain("S3cretPassw0rd!")
        assertThat(credential.auth.toString()).contains("[REDACTED]")
    }

    @Test
    fun `envelope can be read back with a typed payload`() {
        val jobId = UUID.randomUUID()
        val envelope = Envelope(
            type = MessageType.JOB_COMPLETED,
            correlationId = UUID.randomUUID(),
            payload = JobCompletedPayload(jobId, 1, JobStats(findingsCount = 0, requestsSent = 3, durationMs = 10)),
        )
        val raw = Json.readEnvelope(Json.write(envelope))
        assertThat(raw.type).isEqualTo(MessageType.JOB_COMPLETED)
        assertThat(raw.messageId).isEqualTo(envelope.messageId)
        val payload = Json.payloadAs(raw, JobCompletedPayload::class.java)
        assertThat(payload.jobId).isEqualTo(jobId)
        assertThat(payload.stats.requestsSent).isEqualTo(3)
    }
}
