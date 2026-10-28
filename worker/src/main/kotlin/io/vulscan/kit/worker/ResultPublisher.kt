package io.vulscan.kit.worker

import io.vulscan.kit.contracts.Envelope
import io.vulscan.kit.contracts.FindingPayload
import io.vulscan.kit.contracts.FindingReportedPayload
import io.vulscan.kit.contracts.JobCompletedPayload
import io.vulscan.kit.contracts.JobErrorCode
import io.vulscan.kit.contracts.JobFailedPayload
import io.vulscan.kit.contracts.JobStats
import io.vulscan.kit.contracts.Json
import io.vulscan.kit.contracts.KafkaTopics
import io.vulscan.kit.contracts.MessageType
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.UUID
import java.util.concurrent.TimeUnit

interface ResultPublisher {
    fun publishFinding(jobId: UUID, attempt: Int, correlationId: UUID, finding: FindingPayload)

    fun publishCompleted(jobId: UUID, attempt: Int, correlationId: UUID, stats: JobStats)

    fun publishFailed(
        jobId: UUID,
        attempt: Int,
        correlationId: UUID,
        code: JobErrorCode,
        message: String,
        retryable: Boolean,
        stats: JobStats? = null,
    )
}

/** Публикует события в `scan.job-events.v1`, ключ = jobId (порядок событий одного задания сохраняется). */
class KafkaResultPublisher(
    private val producer: KafkaProducer<String, String>,
    private val sendTimeoutSec: Long = 10,
    private val attempts: Int = 3,
) : ResultPublisher {

    override fun publishFinding(jobId: UUID, attempt: Int, correlationId: UUID, finding: FindingPayload) {
        send(jobId, Envelope(type = MessageType.FINDING_REPORTED, correlationId = correlationId, payload = FindingReportedPayload(jobId, attempt, finding)))
    }

    override fun publishCompleted(jobId: UUID, attempt: Int, correlationId: UUID, stats: JobStats) {
        send(jobId, Envelope(type = MessageType.JOB_COMPLETED, correlationId = correlationId, payload = JobCompletedPayload(jobId, attempt, stats)))
    }

    override fun publishFailed(
        jobId: UUID,
        attempt: Int,
        correlationId: UUID,
        code: JobErrorCode,
        message: String,
        retryable: Boolean,
        stats: JobStats?,
    ) {
        val payload = JobFailedPayload(jobId, attempt, code, message.take(500), retryable, stats)
        send(jobId, Envelope(type = MessageType.JOB_FAILED, correlationId = correlationId, payload = payload))
    }

    private fun send(jobId: UUID, envelope: Envelope<*>) {
        val value = Json.write(envelope)
        var last: Exception? = null
        repeat(attempts) {
            try {
                producer.send(ProducerRecord(KafkaTopics.JOB_EVENTS, jobId.toString(), value)).get(sendTimeoutSec, TimeUnit.SECONDS)
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("Failed to publish ${envelope.type} for job $jobId", last)
    }
}
