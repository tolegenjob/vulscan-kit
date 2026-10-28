package io.vulscan.kit.contracts

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ContractSchemaTest {

    companion object {
        private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

        /** Префикс имени примера -> файл схемы. Порядок важен: более длинные префиксы первыми. */
        private val schemaByPrefix = listOf(
            "scan-job" to "scan-job.v1.json",
            "finding" to "finding-reported.v1.json",
            "job-completed" to "job-completed.v1.json",
            "job-failed" to "job-failed.v1.json",
            "internal-claim" to "internal-claim.v1.json",
            "internal-heartbeat" to "internal-heartbeat.v1.json",
        )

        val validExamples = listOf(
            "scan-job-xss.json",
            "scan-job-sqli.json",
            "scan-job-idor.json",
            "finding-xss.json",
            "finding-sqli.json",
            "finding-idor.json",
            "job-completed.json",
            "job-failed.json",
            "internal-claim.json",
            "internal-heartbeat.json",
        )

        val invalidExamples = listOf(
            "scan-job-xss.missing-deadline.json",
            "scan-job-xss.two-option-branches.json",
            "scan-job-xss.unknown-module.json",
            "finding-xss.confidence-100.json",
            "finding-xss.bad-fingerprint.json",
            "job-failed.retryable-scope-violation.json",
            "job-completed.negative-count.json",
        )

        @JvmStatic
        fun valid(): List<String> = validExamples

        @JvmStatic
        fun invalid(): List<String> = invalidExamples

        @JvmStatic
        fun validEnvelopes(): List<String> = validExamples.filterNot { it.startsWith("internal-") }

        private fun schemaFileFor(exampleName: String): String =
            schemaByPrefix.first { exampleName.startsWith(it.first) }.second

        fun validate(schemaFile: String, json: String): List<String> {
            val schema = registry.getSchema(SchemaLocation.of("classpath:schemas/$schemaFile"))
            return schema.validate(json, InputFormat.JSON).map { it.toString() }
        }
    }

    @ParameterizedTest
    @MethodSource("valid")
    fun `valid example conforms to its schema`(name: String) {
        val errors = validate(schemaFileFor(name), ContractExamples.valid(name))
        assertThat(errors).`as`("schema errors for $name").isEmpty()
    }

    @ParameterizedTest
    @MethodSource("invalid")
    fun `invalid example is rejected by its schema`(name: String) {
        val errors = validate(schemaFileFor(name), ContractExamples.invalid(name))
        assertThat(errors).`as`("expected schema errors for $name").isNotEmpty()
    }

    /** DTO -> JSON -> схема: сериализация Kotlin-классов не должна выходить за рамки контракта. */
    @ParameterizedTest
    @MethodSource("validEnvelopes")
    fun `example survives DTO roundtrip and still conforms to schema`(name: String) {
        val raw = Json.readEnvelope(ContractExamples.valid(name))
        val payload: Any = when (raw.type) {
            MessageType.SCAN_JOB -> Json.payloadAs(raw, ScanJobPayload::class.java)
            MessageType.FINDING_REPORTED -> Json.payloadAs(raw, FindingReportedPayload::class.java)
            MessageType.JOB_COMPLETED -> Json.payloadAs(raw, JobCompletedPayload::class.java)
            MessageType.JOB_FAILED -> Json.payloadAs(raw, JobFailedPayload::class.java)
        }
        val written = Json.write(
            Envelope(
                schemaVersion = raw.schemaVersion,
                messageId = raw.messageId,
                type = raw.type,
                correlationId = raw.correlationId,
                occurredAt = raw.occurredAt,
                payload = payload,
            ),
        )
        val errors = validate(schemaFileFor(name), written)
        assertThat(errors).`as`("schema errors after roundtrip for $name:\n$written").isEmpty()
    }

    @ParameterizedTest
    @MethodSource("valid")
    fun `claim and heartbeat examples map to DTO`(name: String) {
        if (name == "internal-claim.json") {
            val claim = Json.read(ContractExamples.valid(name), ClaimRequest::class.java)
            assertThat(claim.attempt).isEqualTo(1)
            assertThat(claim.workerId).isEqualTo("xss-7f9c-1")
        }
        if (name == "internal-heartbeat.json") {
            val hb = Json.read(ContractExamples.valid(name), HeartbeatRequest::class.java)
            assertThat(hb.requestsSent).isEqualTo(120)
        }
    }
}
