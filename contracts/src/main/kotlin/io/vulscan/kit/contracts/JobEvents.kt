package io.vulscan.kit.contracts

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/** Payload сообщения JOB_COMPLETED. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JobCompletedPayload(
    val jobId: UUID,
    val attempt: Int,
    val stats: JobStats,
)

/** Payload сообщения JOB_FAILED. retryable=true допустим только для TARGET_UNREACHABLE и INTERNAL_ERROR. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JobFailedPayload(
    val jobId: UUID,
    val attempt: Int,
    val errorCode: JobErrorCode,
    /** Без стек-трейсов и секретов, не длиннее 500 символов. */
    val message: String,
    val retryable: Boolean,
    val stats: JobStats? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JobStats(
    val findingsCount: Int,
    val requestsSent: Int,
    val durationMs: Long,
    val endpointsTested: Int = 0,
    val endpointsSkipped: List<SkippedEndpoint> = emptyList(),
    val inconclusive: Int = 0,
    val budgetExhausted: Boolean = false,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SkippedEndpoint(
    val endpointId: UUID,
    /** Например OUT_OF_SCOPE, BUDGET, NOT_IDEMPOTENT, UNSUPPORTED_LOCATION. */
    val reason: String,
)
